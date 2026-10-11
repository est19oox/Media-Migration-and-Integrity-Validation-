
package mmiv.dto;

/**
 * Summary response for CSV manifest processing.
 */
public record ManifestResponse(
        int total,
        long matched,
        long mismatched
) {
}
