package mmiv.integration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UploadNamesTest {
 @Test void duplicateNamesUseAsciiSuffixBeforeExtension(){assertEquals("report (2).pdf",UploadNames.available("report.pdf",Set.of("report.pdf")));assertEquals("report (3).pdf",UploadNames.available("report.pdf",Set.of("report.pdf","report (2).pdf")));}
 @Test void differentLogicalDirectoriesKeepSameBasename(){assertEquals("semester2/report.pdf",UploadNames.available("semester2/report.pdf",Set.of("semester1/report.pdf")));assertEquals("semester1/report (2).pdf",UploadNames.available("semester1/report.pdf",Set.of("semester1/report.pdf")));}
}
