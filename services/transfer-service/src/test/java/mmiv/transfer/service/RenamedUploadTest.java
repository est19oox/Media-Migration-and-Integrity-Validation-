package mmiv.transfer.service;
import mmiv.transfer.validation.TransferRequestValidator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;
class RenamedUploadTest {
 @Test void acceptsAsciiCollisionSuffixesAndPreservesBytes()throws Exception {var preparer=new TransferSourcePreparer(new TransferRequestValidator("/unused"),104857600);var file=new MockMultipartFile("file","report.pdf","application/pdf","bytes".getBytes());for(String name:new String[]{"report (2).pdf","report (10).pdf","report (100).pdf"}){try(var staged=preparer.prepare(file,name,"SW001/migrations/id/"+name)){assertEquals(name,staged.fileName());assertArrayEquals(file.getBytes(),java.nio.file.Files.readAllBytes(staged.path()));}}}
 @Test void rejectsUnrelatedAssignedNames(){var preparer=new TransferSourcePreparer(new TransferRequestValidator("/unused"),104857600);var file=new MockMultipartFile("file","report.pdf","application/pdf","bytes".getBytes());for(String name:new String[]{"other.pdf","report (1).pdf","report (2).exe","report （2）.pdf"})assertThrows(IllegalArgumentException.class,()->preparer.prepare(file,name,"SW001/"+name));}
}
