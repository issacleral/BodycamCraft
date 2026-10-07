use oozextract::Extractor;

/// Decompresses one Oodle block. Returns the number of bytes written, or -1 on failure.
///
/// # Safety
/// `src` must point to `src_len` readable bytes and `dst` to `dst_len` writable bytes.
#[no_mangle]
pub unsafe extern "C" fn bcoodle_decompress(
    src: *const u8,
    src_len: usize,
    dst: *mut u8,
    dst_len: usize,
) -> i64 {
    if src.is_null() || dst.is_null() {
        return -1;
    }
    let input = std::slice::from_raw_parts(src, src_len);
    let output = std::slice::from_raw_parts_mut(dst, dst_len);
    let mut extractor = Extractor::new();
    match extractor.read_from_slice(input, output) {
        Ok(n) => n as i64,
        Err(_) => -1,
    }
}
