package mmiv.repository;

import mmiv.model.MediaMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

@Repository
public class FileMetadataRepository {

    private final JdbcTemplate jdbcTemplate;

    public FileMetadataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Check whether the same file already exists
    
    // Check whether the same file already exists.
    // Oracle treats empty strings as NULL.
    public boolean exists(String fileName, String srcDir) {

        String sql = """
                SELECT COUNT(*)
                FROM FILE_METADATA
                WHERE FILE_NAME = ?
                AND (
                    SRC_DIR = ?
                    OR (SRC_DIR IS NULL AND ? IS NULL)
                )
                """;

        String normalizedSrcDir =
                (srcDir == null || srcDir.isEmpty())
                        ? null
                        : srcDir;

        Integer count = jdbcTemplate.queryForObject(
                sql,
                Integer.class,
                fileName,
                normalizedSrcDir,
                normalizedSrcDir
        );

        return count != null && count > 0;
    }


    // Save one file's metadata into Oracle
    public int save(MediaMetadata metadata) {

        String sql = """
                INSERT INTO FILE_METADATA
                (FILE_NAME, MEDIA_TYPE, FILE_SIZE, SRC_DIR, CREATED_DATE)
                VALUES (?, ?, ?, ?, ?)
                """;

        return jdbcTemplate.update(
                sql,
                metadata.getFileName(),
                metadata.getMediaType(),
                metadata.getFileSize(),
                metadata.getSrcDir(),
                Timestamp.valueOf(metadata.getCreatedDate())
        );
    }
}