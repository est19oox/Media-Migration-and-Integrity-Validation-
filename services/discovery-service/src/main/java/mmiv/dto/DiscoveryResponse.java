
package mmiv.dto;

public record DiscoveryResponse(
        long discovered,
        long accepted,
        long skipped,
        long failed,
        int inserted
) {
}
