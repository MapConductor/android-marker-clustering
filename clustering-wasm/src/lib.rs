use std::collections::HashMap;
use std::f64::consts::PI;

const DEG_TO_RAD: f64 = PI / 180.0;
const MAX_SIN_LAT: f64 = 0.9999;
const EARTH_RADIUS_METERS: f64 = 6_371_008.8;
const EARTH_CIRCUMFERENCE_METERS: f64 = 40_075_016.686;
const MAX_DENSE_CELLS: usize = 4;
const MAX_DENSE_CANDIDATES: usize = 50;

// Static output buffers — safe because Wasm is single-threaded.
static mut RESULT_CLUSTER_LATS: Vec<f64> = Vec::new();
static mut RESULT_CLUSTER_LONS: Vec<f64> = Vec::new();
static mut RESULT_CLUSTER_SIZES: Vec<i32> = Vec::new();
static mut RESULT_MEMBER_OFFSETS: Vec<i32> = Vec::new();
static mut RESULT_MEMBER_IDS: Vec<i32> = Vec::new();

// --- Memory management (called from Kotlin via Chicory) ---

#[no_mangle]
pub extern "C" fn wasm_alloc(size: i32) -> i32 {
    let layout = std::alloc::Layout::from_size_align(size as usize, 8).unwrap();
    unsafe { std::alloc::alloc(layout) as i32 }
}

#[no_mangle]
pub extern "C" fn wasm_dealloc(ptr: i32, size: i32) {
    let layout = std::alloc::Layout::from_size_align(size as usize, 8).unwrap();
    unsafe { std::alloc::dealloc(ptr as *mut u8, layout) }
}

// --- Core math (mirrors MarkerClusterStrategy.kt) ---

fn project_to_pixel(lat: f64, lon: f64, zoom: f64, tile_size: f64) -> (f64, f64) {
    let scale = tile_size * (2f64).powf(zoom);
    let sin_lat = (lat * DEG_TO_RAD).sin().clamp(-MAX_SIN_LAT, MAX_SIN_LAT);
    let x = (lon + 180.0) / 360.0 * scale;
    let y = (0.5 - ((1.0 + sin_lat) / (1.0 - sin_lat)).ln() / (4.0 * PI)) * scale;
    (x, y)
}

fn meters_per_pixel(lat: f64, zoom: f64, tile_size: f64) -> f64 {
    let scale = tile_size * (2f64).powf(zoom);
    (EARTH_CIRCUMFERENCE_METERS * (lat * DEG_TO_RAD).cos()) / scale
}

fn haversine_distance(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    let d_lat = (lat2 - lat1) * DEG_TO_RAD;
    let d_lon = (lon2 - lon1) * DEG_TO_RAD;
    let a = (d_lat / 2.0).sin().powi(2)
        + (lat1 * DEG_TO_RAD).cos() * (lat2 * DEG_TO_RAD).cos() * (d_lon / 2.0).sin().powi(2);
    let c = 2.0 * a.sqrt().atan2((1.0 - a).sqrt());
    EARTH_RADIUS_METERS * c
}

// Mirrors selectDenseCenter() in MarkerClusterStrategy.kt
fn select_dense_center(
    lats: &[f64],
    lons: &[f64],
    members: &[usize],
    zoom: f64,
    cluster_radius_px: f64,
    tile_size: f64,
) -> (f64, f64) {
    if members.is_empty() {
        return (0.0, 0.0);
    }
    if members.len() == 1 {
        return (lats[members[0]], lons[members[0]]);
    }

    let points: Vec<(f64, f64)> = members
        .iter()
        .map(|&i| project_to_pixel(lats[i], lons[i], zoom, tile_size))
        .collect();

    let cell_size = cluster_radius_px;
    let mut cell_map: HashMap<(i32, i32), Vec<usize>> = HashMap::new();
    for (pt_idx, &(px, py)) in points.iter().enumerate() {
        let cx = (px / cell_size).floor() as i32;
        let cy = (py / cell_size).floor() as i32;
        cell_map.entry((cx, cy)).or_default().push(pt_idx);
    }

    let mut sorted_cells: Vec<_> = cell_map.iter().collect();
    sorted_cells.sort_by(|a, b| b.1.len().cmp(&a.1.len()));

    let candidates: Vec<usize> = sorted_cells
        .iter()
        .take(MAX_DENSE_CELLS)
        .flat_map(|(_, pts)| pts.iter().copied())
        .take(MAX_DENSE_CANDIDATES)
        .collect();

    let radius_sq = cell_size * cell_size;
    let mut best_pt_idx = candidates.first().copied().unwrap_or(0);
    let mut best_neighbor_count = -1i32;
    let mut best_total_distance = f64::MAX;

    for &cand_pt_idx in &candidates {
        let (cx, cy) = points[cand_pt_idx];
        let cell_x = (cx / cell_size).floor() as i32;
        let cell_y = (cy / cell_size).floor() as i32;

        let mut neighbor_count = 0i32;
        let mut total_distance = 0.0f64;

        for dx in -1i32..=1 {
            for dy in -1i32..=1 {
                if let Some(neighbors) = cell_map.get(&(cell_x + dx, cell_y + dy)) {
                    for &other_pt_idx in neighbors {
                        let (ox, oy) = points[other_pt_idx];
                        let dxp = cx - ox;
                        let dyp = cy - oy;
                        let dist_sq = dxp * dxp + dyp * dyp;
                        if dist_sq <= radius_sq {
                            neighbor_count += 1;
                            total_distance += dist_sq.sqrt();
                        }
                    }
                }
            }
        }

        if neighbor_count > best_neighbor_count
            || (neighbor_count == best_neighbor_count && total_distance < best_total_distance)
        {
            best_neighbor_count = neighbor_count;
            best_total_distance = total_distance;
            best_pt_idx = cand_pt_idx;
        }
    }

    let original_idx = members[best_pt_idx];
    (lats[original_idx], lons[original_idx])
}

// Mirrors mergeClusters() in MarkerClusterStrategy.kt
fn merge_clusters(
    lats: &[f64],
    lons: &[f64],
    zoom: f64,
    cluster_radius_px: f64,
    tile_size: f64,
) -> Vec<(f64, f64, Vec<usize>)> {
    if lats.is_empty() {
        return Vec::new();
    }

    // Assign each marker to a grid cell
    let mut cell_map: HashMap<(i32, i32), Vec<usize>> = HashMap::new();
    for i in 0..lats.len() {
        let (px, py) = project_to_pixel(lats[i], lons[i], zoom, tile_size);
        let cell_x = (px / cluster_radius_px).floor() as i32;
        let cell_y = (py / cluster_radius_px).floor() as i32;
        cell_map.entry((cell_x, cell_y)).or_default().push(i);
    }

    // Sort for deterministic output (matches Kotlin's sortedWith compareBy x then y)
    let mut candidates: Vec<((i32, i32), Vec<usize>)> = cell_map.into_iter().collect();
    candidates.sort_by(|a, b| a.0.cmp(&b.0));

    let cell_to_idx: HashMap<(i32, i32), usize> = candidates
        .iter()
        .enumerate()
        .map(|(i, (cell, _))| (*cell, i))
        .collect();

    let mut visited = vec![false; candidates.len()];
    let mut merged: Vec<(f64, f64, Vec<usize>)> = Vec::new();

    for i in 0..candidates.len() {
        if visited[i] {
            continue;
        }
        visited[i] = true;

        let (seed_cell, ref seed_members) = candidates[i];
        let seed_lat = lats[seed_members[0]];
        let seed_lon = lons[seed_members[0]];
        let seed_mpp = meters_per_pixel(seed_lat, zoom, tile_size);

        let mut all_members: Vec<usize> = seed_members.clone();

        for dx in -1i32..=1 {
            for dy in -1i32..=1 {
                if dx == 0 && dy == 0 {
                    continue;
                }
                let neighbor_cell = (seed_cell.0 + dx, seed_cell.1 + dy);
                let neighbor_idx = match cell_to_idx.get(&neighbor_cell) {
                    Some(&idx) => idx,
                    None => continue,
                };
                if visited[neighbor_idx] {
                    continue;
                }

                let neighbor_members = &candidates[neighbor_idx].1;
                let neighbor_lat = lats[neighbor_members[0]];
                let neighbor_lon = lons[neighbor_members[0]];
                let neighbor_mpp = meters_per_pixel(neighbor_lat, zoom, tile_size);

                let threshold = cluster_radius_px * seed_mpp.max(neighbor_mpp);
                let dist = haversine_distance(seed_lat, seed_lon, neighbor_lat, neighbor_lon);

                if dist <= threshold {
                    visited[neighbor_idx] = true;
                    all_members.extend_from_slice(neighbor_members);
                }
            }
        }

        let center =
            select_dense_center(lats, lons, &all_members, zoom, cluster_radius_px, tile_size);
        merged.push((center.0, center.1, all_members));
    }

    merged
}

// --- Exported Wasm functions ---

/// Main entry point. Reads marker data from `lats_ptr`/`lons_ptr` (allocated via `wasm_alloc`),
/// runs grid-assignment and greedy merge, stores results in static buffers, and returns the
/// number of merged groups. `minClusterSize` splitting is intentionally left to the Kotlin caller.
// Wasm is single-threaded; mutable static access is safe here.
#[allow(static_mut_refs)]
#[no_mangle]
pub extern "C" fn compute_clusters(
    lats_ptr: i32,
    lons_ptr: i32,
    count: i32,
    zoom: f64,
    cluster_radius_px: f64,
    tile_size: f64,
) -> i32 {
    let count = count as usize;
    unsafe {
        RESULT_CLUSTER_LATS.clear();
        RESULT_CLUSTER_LONS.clear();
        RESULT_CLUSTER_SIZES.clear();
        RESULT_MEMBER_OFFSETS.clear();
        RESULT_MEMBER_IDS.clear();
    }
    if count == 0 {
        return 0;
    }

    let lats = unsafe { std::slice::from_raw_parts(lats_ptr as *const f64, count) };
    let lons = unsafe { std::slice::from_raw_parts(lons_ptr as *const f64, count) };

    let results = merge_clusters(lats, lons, zoom, cluster_radius_px, tile_size);

    unsafe {
        let mut offset = 0i32;
        for (center_lat, center_lon, members) in &results {
            RESULT_CLUSTER_LATS.push(*center_lat);
            RESULT_CLUSTER_LONS.push(*center_lon);
            RESULT_CLUSTER_SIZES.push(members.len() as i32);
            RESULT_MEMBER_OFFSETS.push(offset);
            for &member_idx in members {
                RESULT_MEMBER_IDS.push(member_idx as i32);
            }
            offset += members.len() as i32;
        }
        RESULT_CLUSTER_LATS.len() as i32
    }
}

/// Pointer to result cluster center latitudes (f64 array).
// Wasm is single-threaded; shared reference to mutable static is safe here.
#[allow(static_mut_refs)]
#[no_mangle]
pub extern "C" fn get_result_cluster_lats_ptr() -> i32 {
    unsafe { RESULT_CLUSTER_LATS.as_ptr() as i32 }
}

/// Pointer to result cluster center longitudes (f64 array).
#[allow(static_mut_refs)]
#[no_mangle]
pub extern "C" fn get_result_cluster_lons_ptr() -> i32 {
    unsafe { RESULT_CLUSTER_LONS.as_ptr() as i32 }
}

/// Pointer to result cluster member counts (i32 array, size=1 means individual marker).
#[allow(static_mut_refs)]
#[no_mangle]
pub extern "C" fn get_result_cluster_sizes_ptr() -> i32 {
    unsafe { RESULT_CLUSTER_SIZES.as_ptr() as i32 }
}

/// Pointer to result member offsets (i32 array; index into member_ids for each cluster).
#[allow(static_mut_refs)]
#[no_mangle]
pub extern "C" fn get_result_member_offsets_ptr() -> i32 {
    unsafe { RESULT_MEMBER_OFFSETS.as_ptr() as i32 }
}

/// Pointer to flat array of original marker indices belonging to each cluster.
#[allow(static_mut_refs)]
#[no_mangle]
pub extern "C" fn get_result_member_ids_ptr() -> i32 {
    unsafe { RESULT_MEMBER_IDS.as_ptr() as i32 }
}

/// Total number of entries in the member_ids array.
#[allow(static_mut_refs)]
#[no_mangle]
pub extern "C" fn get_result_member_ids_len() -> i32 {
    unsafe { RESULT_MEMBER_IDS.len() as i32 }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn project_to_pixel_origin() {
        let (x, y) = project_to_pixel(0.0, 0.0, 0.0, 256.0);
        assert!((x - 128.0).abs() < 1e-6, "x={x}");
        assert!((y - 128.0).abs() < 1e-6, "y={y}");
    }

    #[test]
    fn haversine_tokyo_osaka() {
        // Tokyo (35.68, 139.69) to Osaka (34.69, 135.50) ≈ 397 km
        let dist = haversine_distance(35.68, 139.69, 34.69, 135.50);
        assert!((dist - 397_000.0).abs() < 5_000.0, "dist={dist}");
    }

    #[test]
    fn merge_clusters_empty() {
        let result = merge_clusters(&[], &[], 10.0, 90.0, 256.0);
        assert!(result.is_empty());
    }

    #[test]
    fn merge_clusters_nearby_points() {
        // Four points very close together should merge into one cluster
        let lats = [35.681, 35.682, 35.681, 35.682];
        let lons = [139.767, 139.767, 139.768, 139.768];
        let result = merge_clusters(&lats, &lons, 14.0, 90.0, 256.0);
        assert_eq!(result.len(), 1, "expected 1 merged cluster, got {}", result.len());
        assert_eq!(result[0].2.len(), 4);
    }

    #[test]
    fn merge_clusters_distant_points() {
        // Tokyo and Osaka — should remain separate at zoom 10
        let lats = [35.681, 34.693];
        let lons = [139.767, 135.502];
        let result = merge_clusters(&lats, &lons, 10.0, 90.0, 256.0);
        assert_eq!(result.len(), 2, "expected 2 separate clusters, got {}", result.len());
    }
}
