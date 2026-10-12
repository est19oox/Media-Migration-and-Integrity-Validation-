package mmiv.dto;

/**
 * Placeholder request DTO for the Discovery Service REST API.
 *
 * <p>This record intentionally declares no components. The request shape is
 * not finalised, and inventing fields now would create a contract that later
 * work has to undo.
 *
 * <p>Add components to the record header when the contract is agreed; because
 * this is a record, that is a one-line change and Jackson binding continues to
 * work by component name.
 *
 * <p>Note the existing model classes are domain objects, not transport DTOs,
 * and must not be exposed directly as a request body:
 * {@code mmiv.model.MediaMetadata}, {@code mmiv.model.MigrationFileInventory}.
 */
public record DiscoveryRequest() {

    // TODO Decide, with the team, which of these the request actually carries
    //      before adding any component:
    //        - the root directory to scan, or nothing at all if the root stays
    //          configuration-driven via discovery.root / DISCOVERY_ROOT;
    //        - a CSV manifest reference (path or upload), depending on how a
    //          manifest is supplied at runtime;
    //        - a caller/destination identifier, which depends on how a target
    //          destination is determined.
}
