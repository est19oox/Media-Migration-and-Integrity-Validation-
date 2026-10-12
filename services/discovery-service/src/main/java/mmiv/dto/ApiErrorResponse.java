package mmiv.dto;

/**
 * Placeholder response body for Discovery Service API errors.
 *
 * <p>This record intentionally declares no components, following the same
 * approach as {@link DiscoveryRequest}: the error contract is not agreed yet,
 * and inventing fields now would create a shape that later work has to undo.
 *
 * <p>An empty record compiles and is serialisable, so it can be wired into an
 * exception handler immediately and given components when the team agrees the
 * contract. Because this is a record, adding a component is a one-line change
 * and Jackson keeps binding by component name.
 *
 * <p>Nothing references this type yet. It is a reserved placeholder only.
 */
public record ApiErrorResponse() {

    // TODO Decide, with the team, which of these the error body actually
    //      carries before adding any component. Candidates gathered while
    //      reviewing the current implementation:
    //
    //        - timestamp      : when the failure was recorded. Decide the type
    //                           (java.time.Instant vs OffsetDateTime vs String)
    //                           and whether it is ISO-8601 in UTC.
    //        - status         : the numeric HTTP status, duplicating the status
    //                           line. Useful for clients that log the body only.
    //        - error          : the short reason phrase, for example
    //                           "Bad Request" or "Service Unavailable".
    //        - message        : a human-readable summary. Decide explicitly
    //                           whether raw exception text is allowed here;
    //                           JDBC/Oracle messages can disclose schema and
    //                           connection details.
    //        - validationErrors : the per-row failures from
    //                           mmiv.csv.MetadataCsvValidationError
    //                           (rowNumber, fieldName, reason). This is the
    //                           part callers most need for an invalid manifest,
    //                           and the part the current implementation throws
    //                           away. Decide whether the wire type is
    //                           MetadataCsvValidationError itself or a
    //                           separate transport type: the existing model
    //                           classes are domain objects, and
    //                           mmiv.model.MediaMetadata is already documented
    //                           as not being a transport DTO.
    //        - path           : the request path that failed, useful when a
    //                           response body is read out of context.
    //
    //      Also decide, before adding anything:
    //
    //        - whether this shape is shared with the other MMIV services or is
    //          discovery-specific. There is no shared error contract in the
    //          repository today, and docs/api/api-contracts.md is still marked
    //          "Draft - Team Discussion Required".
    //        - whether to align with RFC 9457 Problem Details
    //          (application/problem+json), which Spring can produce natively
    //          via ProblemDetail / ErrorResponse. Choosing that instead of a
    //          bespoke record would make this class unnecessary.
    //
    //      Do not implement any mapping or serialisation logic here: this type
    //      stays a plain data carrier, and the handler decides how to build it.
}
