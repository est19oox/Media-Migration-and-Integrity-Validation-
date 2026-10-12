package mmiv.integration;

import org.springframework.stereotype.Component;

/**
 * Placeholder outbound client for the Transfer Service.
 *
 * <p>This class is deliberately empty. It reserves the
 * {@code mmiv.integration} package so the Discovery to Transfer handoff can be
 * implemented in one place later. It performs no HTTP requests and contains no
 * transfer logic.
 *
 * <p>Nothing in the Discovery Service calls the Transfer Service today, and
 * the Discovery Service currently has no HTTP client dependency at all (see
 * pom.xml). The handoff is also not an agreed interface yet:
 * {@code docs/architecture/architecture.md} and
 * {@code docs/api/api-contracts.md} are both still drafts, and neither lists a
 * Discovery to Transfer relationship.
 */
@Component
public class TransferClient {

    // TODO Confirm the direction and the contract before writing any code.
    //      Relevant facts gathered from the current codebase:
    //        - The Transfer Service exposes no inbound queue. Its only trigger
    //          is a synchronous POST /api/v1/transfers with a JSON body of
    //          {sourcePath, destinationKey, contentType}.
    //        - sourcePath must resolve on the Transfer Service's own container
    //          filesystem, under its configured media root (/media, bind
    //          mounted read only from /var/lib/mmiv/media). The Discovery
    //          Service currently records only a root-relative srcDir, so it
    //          cannot produce a usable sourcePath yet.
    //        - There is no target destination concept anywhere in the
    //          repository. The Transfer Service writes to one configured
    //          bucket and derives the object key from destinationKey.

    // TODO Add the base URL or service id and the HTTP client once the team
    //      agrees who calls whom. Note that peers are addressed by Eureka
    //      service id elsewhere in this repository, and that adding a client
    //      (for example spring-cloud-starter-openfeign, or Spring's RestClient)
    //      is a dependency change that this skeleton phase deliberately avoids.
}
