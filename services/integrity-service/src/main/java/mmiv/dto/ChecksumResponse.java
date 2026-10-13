package mmiv.dto;

public record ChecksumResponse(String fileName, long fileSize, String algorithm, String checksum) {}
