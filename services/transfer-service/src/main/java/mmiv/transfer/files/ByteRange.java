package mmiv.transfer.files;

/** Supports one bytes range (closed, open-ended or suffix); multi-range is rejected. */
public record ByteRange(long start, long end) {
    public long length() { return end - start + 1; }
    public String s3Header() { return "bytes=" + start + "-" + end; }
    public String contentRange(long size) { return "bytes " + start + "-" + end + "/" + size; }
    public static ByteRange parse(String header, long size) {
        if (header == null) return null;
        if (size == 0 || !header.matches("bytes=[0-9]*-[0-9]*")) throw new FileRangeException(size);
        String[] bounds = header.substring(6).split("-", -1);
        try {
            long start, end;
            if (bounds[0].isEmpty()) {
                long suffix = Long.parseLong(bounds[1]);
                if (suffix <= 0) throw new FileRangeException(size);
                start = suffix >= size ? 0 : size - suffix;
                end = size - 1;
            } else {
                start = Long.parseLong(bounds[0]);
                end = bounds[1].isEmpty() ? size - 1 : Math.min(Long.parseLong(bounds[1]), size - 1);
            }
            if (start < 0 || start >= size || end < start) throw new FileRangeException(size);
            return new ByteRange(start, end);
        } catch (NumberFormatException e) { throw new FileRangeException(size); }
    }
}
