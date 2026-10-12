package mmiv.integration;

import java.nio.file.*;
import java.util.*;
import java.io.IOException;
import mmiv.events.EventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MetadataService {
    private final PermissionClient permissions;
    private final MetadataPlanRepository plans;
    private final EventPublisher events;
    private final Path root;
    private final String destination;
    private final String bucket;
    public MetadataService(PermissionClient permissions, MetadataPlanRepository plans, EventPublisher events,
            @Value("${discovery.root}") String root,
            @Value("${mmiv.metadata.destination-id:${mmiv.s3.bucket:media-migration-dev-landing}}") String destination,
            @Value("${mmiv.s3.bucket:media-migration-dev-landing}") String bucket) {
        this.permissions=permissions; this.plans=plans; this.events=events; this.root=Path.of(root); this.destination=destination; this.bucket=bucket;
    }
    public MetadataPlan create(Jwt jwt, CreatePlan request, boolean upload) { return create(jwt,request,upload,null); }
    public MetadataPlan create(Jwt jwt, CreatePlan request, boolean upload, UUID verificationRun) {
        if (request == null || request.destinationId() == null || !request.destinationId().matches("[A-Za-z0-9][A-Za-z0-9 _.-]{0,79}") || bucket.equals(request.destinationId()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a registered folder");
        permissions.requireFolder(jwt.getTokenValue(),request.destinationId());
        permissions.require(jwt.getTokenValue(), jwt.getSubject(), request.destinationId(),"WRITE");
        String name; String source = null; long size;
        if (upload) { name=request.fileName(); size=request.sizeBytes()==null?0:request.sizeBytes(); }
        else {
            try {
                if (request.sourcePath()==null || Path.of(request.sourcePath()).isAbsolute()) throw new IOException();
                var base=root.toRealPath(); var path=base.resolve(request.sourcePath()).toRealPath();
                if (!path.startsWith(base) || !Files.isRegularFile(path) || !Files.isReadable(path)) throw new IOException();
                source=base.relativize(path).toString().replace('\\','/'); name=path.getFileName().toString(); size=Files.size(path);
            } catch (IOException | InvalidPathException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid media source"); }
        }
        if (name==null || !name.matches("[\\p{L}\\p{N}._ ()-]{1,200}") || name.equals(".") || name.equals("..") || size<=0 || size>104857600)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid media file");
        String contentType = contentType(name);
        String objectPath=upload && request.relativePath()!=null?request.relativePath():name;
        if(objectPath.startsWith("/") || objectPath.contains("\\") || !objectPath.endsWith(name)
                || !objectPath.substring(objectPath.lastIndexOf('/')+1).equals(name)
                || objectPath.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>900
                || java.util.Arrays.stream(objectPath.split("/",-1)).anyMatch(part->part.equals(".") || part.equals("..") || part.isBlank() || !part.matches("[\\p{L}\\p{N}._ ()-]{1,200}")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid relative media path");
        var id=UUID.randomUUID(); var file=UUID.randomUUID();
        if((request.destinationId()+"/migrations/"+id+"/"+objectPath).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1024)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"File path is too long");
        var plan=new MetadataPlan(id,id,file,jwt.getSubject(),request.destinationId(),bucket,request.destinationId()+"/migrations/"+id+"/"+objectPath,source,name,contentType,size,upload?"UPLOAD":"SOURCE",objectPath,verificationRun);
        plans.save(plan);
        events.migration("MIGRATION_STARTED","migration.started",id,null,
                Map.of("initiatedBy",jwt.getSubject(),"source",Map.of("type","DIRECT_UPLOAD","rootId","media")));
        events.migration("FILE_DISCOVERY_COMPLETED","discovery.file.completed",id,file,
                Map.of("status","ACCEPTED","fileName",name,"relativePath",source==null?objectPath:source,
                        "mediaType",contentType.startsWith("image/")?"IMAGE":contentType.startsWith("video/")?"VIDEO":contentType.startsWith("audio/")?"AUDIO":"DOCUMENT","format",name.substring(name.lastIndexOf('.')+1).toUpperCase(Locale.ROOT),"sizeBytes",size));
        events.migration("MIGRATION_DISCOVERY_COMPLETED","discovery.migration.completed",id,null,
                Map.of("status","COMPLETED","totalFiles",1,"acceptedFiles",1,"rejectedFiles",0));
        return plan;
    }
    public MetadataPlan get(Jwt jwt, UUID id, String action) {
        var found="READ".equals(action)?plans.findAny(id):plans.find(id,jwt.getSubject());
        var plan=found.orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Metadata not found"));
        if("READ".equals(action) && !plan.destinationKey().startsWith(plan.destinationId()+"/") && !plan.ownerId().equals(jwt.getSubject()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Metadata not found");
        permissions.require(jwt.getTokenValue(),jwt.getSubject(),plan.destinationId(),action);
        if("WRITE".equals(action) && plan.verificationRunId()!=null && plans.isClosed(plan.ownerId(),plan.verificationRunId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Verification run is closed");
        return plan;
    }
    public String destination() { return destination; }
    public List<MetadataPlan> folderPlans(Jwt jwt,String folder,int page) {
        if(page<0 || page>100000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page");
        permissions.requireFolder(jwt.getTokenValue(),folder);
        permissions.require(jwt.getTokenValue(),jwt.getSubject(),folder,"READ");
        return plans.folderPlans(folder,page);
    }
    private static String contentType(String name) {
        String extension=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        return switch(extension) { case "mp4"->"video/mp4"; case "mp3"->"audio/mpeg"; case "png"->"image/png";
            case "txt"->"text/plain"; case "jpg", "jpeg"->"image/jpeg"; case "doc"->"application/msword"; case "docx"->"application/vnd.openxmlformats-officedocument.wordprocessingml.document"; case "pdf"->"application/pdf"; case "ppt"->"application/vnd.ms-powerpoint";
            case "pptx"->"application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default->throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported media type"); };
    }
    public record CreatePlan(String destinationId,String sourcePath,String fileName,Long sizeBytes,String relativePath) {
        public CreatePlan(String destinationId,String sourcePath,String fileName,long sizeBytes){this(destinationId,sourcePath,fileName,sizeBytes,null);}
    }
}
