package mmiv.controller;

/**
 * Reserved skeleton for web-layer (MockMvc) tests of the Discovery REST API.
 *
 * <p>This class deliberately declares no test methods and no Spring test
 * annotations yet, so it runs nothing and changes nothing. It exists to hold
 * the mapping between each intended test and the exact annotation package that
 * Spring Boot 4.1.1 requires, because those packages moved in Boot 4 and the
 * Boot 3 names no longer compile.
 *
 * <p>Why the annotations are not applied here: annotating this class with
 * {@code @WebMvcTest} would make the Spring TestContext framework build a web
 * slice around {@link DiscoveryController} as soon as the first test method is
 * added. That controller's constructor requires {@code DiscoveryService} and
 * {@code ManifestProcessingService} beans, plus {@code discovery.root} and
 * {@code discovery.manifest-path} properties, none of which the web slice
 * provides; the context would fail to load. Those stubs belong with the first
 * real test, not in a placeholder.
 *
 * <p>The existing {@link DiscoveryControllerTest} is a plain unit test that
 * calls {@code new DiscoveryController(...)} directly. It never starts Spring,
 * so it cannot detect a wrong {@code @PostMapping} path, a wrong JSON field
 * name, or a wrong status code. That gap is exactly what this class is for.
 */
class DiscoveryControllerWebTest {

    // TODO Verified annotation packages for Spring Boot 4.1.1 (checked against
    //      the resolved jars in the local Maven repository, not assumed):
    //
    //        org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
    //            <- MOVED. In Boot 3 this was
    //               org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest.
    //               The Boot 3 package does not exist in Boot 4.1.1.
    //            <- provided by spring-boot-webmvc-test-4.1.1.jar, pulled in
    //               transitively by the spring-boot-starter-webmvc-test
    //               dependency already declared in pom.xml with test scope.
    //        org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
    //            <- also moved, same jar. Already implied by @WebMvcTest, so it
    //               only needs adding if MockMvc is used without @WebMvcTest.
    //        org.springframework.test.web.servlet.MockMvc
    //            <- UNCHANGED, provided by spring-test-7.0.9.jar.
    //        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
    //            <- UNCHANGED, spring-test-7.0.9.jar.
    //        org.springframework.test.web.servlet.result.MockMvcResultMatchers
    //            <- UNCHANGED, spring-test-7.0.9.jar.
    //        org.springframework.test.context.bean.override.mockito.MockitoBean
    //            <- provided by spring-test, not by Spring Boot. Already used by
    //               mmiv.ApplicationTest, so the dependency is present.
    //
    //      No new dependency is required for any of the above.

    // TODO Wire the slice before writing assertions. Required once the class is
    //      annotated with @WebMvcTest:
    //        - @MockitoBean DiscoveryService discoveryService
    //        - @MockitoBean ManifestProcessingService manifestProcessingService
    //        - properties = {"discovery.root=...", "discovery.manifest-path=..."}
    //          because discovery.root has no default value in application.yml,
    //          so the placeholder will not resolve without it.
    //      Consider @WebMvcTest(controllers = DiscoveryController.class) so the
    //      slice cannot widen silently.

    // TODO POST /api/discovery/scan - happy path.
    //      Stub discoverAndSaveWithResult(...) to return a DiscoveryRunResult
    //      wrapping a MigrationFileInventory, then assert HTTP 200 and the JSON
    //      fields discovered / accepted / skipped / failed / inserted.
    //      This is the test that proves the mapping path: DiscoveryControllerTest
    //      calls the method directly and would still pass if the @PostMapping
    //      value were misspelled.

    // TODO POST /api/discovery/manifest - happy path.
    //      Stub processManifest(...) to return a mixed list of
    //      ManifestMatchResult (success + mismatch), then assert HTTP 200 and
    //      JSON fields total / matched / mismatched.
    //      Cover the empty-list case too: it must serialise as 0/0/0, not fail.

    // TODO JSON serialisation contract.
    //      Pin the exact field names and the integer-versus-decimal shape. Note
    //      DiscoveryResponse declares inserted as int while the other four are
    //      long; ManifestResponse declares total as int while matched and
    //      mismatched are long. If the contract later changes, these assertions
    //      are what catches it.

    // TODO Invalid CSV -> HTTP 400.
    //      Requires the handler that DiscoveryExceptionHandler does not have yet.
    //      Stub processManifest(...) to throw MetadataCsvValidationException
    //      carrying MetadataCsvValidationError entries, then assert the status
    //      and that the per-row errors appear in the body.
    //      This test cannot pass before that handler exists - do not write it
    //      with a weaker expectation just to get it green.

    // TODO Missing CSV -> HTTP 404.
    //      Confirm which exception actually fires first. MetadataCsvValidator
    //      returns an error for a missing file rather than throwing, so the
    //      missing-CSV case may surface as MetadataCsvValidationException
    //      instead of NoSuchFileException. Verify before asserting a status.

    // TODO Missing manifest configuration -> HTTP 503.
    //      Construct the controller with a blank manifest path (as
    //      DiscoveryControllerTest already does) and assert 503.
    //      Worth also asserting the response body, since the current
    //      ResponseStatusException reason text is not surfaced to callers under
    //      the default server.error.include-message setting.

    // TODO Database errors -> HTTP 503.
    //      Stub the service to throw a DataAccessException subtype (for example
    //      DataIntegrityViolationException) and assert 503 once the handler
    //      exists.

    // TODO Add a test asserting that a successful call still returns the exact
    //      same body as before these handlers were introduced. The instruction
    //      for this work is that only error paths change.
}
