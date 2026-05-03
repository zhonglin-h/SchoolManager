package com.school.service;

import com.google.api.client.http.FileContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.school.config.BackupProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.backup.enabled", havingValue = "true")
public class DriveBackupService {

    private final Drive googleDrive;
    private final BackupProperties props;

    @Scheduled(cron = "${app.backup.cron:0 0 2 * * *}")
    public void runBackup() {
        String filename = "schooldb-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))
                + ".sql.gz";
        Path backupsDir = Paths.get("./backups");
        Path tempFile = backupsDir.resolve(filename);

        try {
            Files.createDirectories(backupsDir);
            dumpAndCompress(tempFile);
            uploadToDrive(tempFile, filename);
            pruneOldBackups();
            log.info("Backup completed: {}", filename);
        } catch (Exception e) {
            log.error("Backup failed: {}", e.getMessage(), e);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ex) {
                log.warn("Failed to delete temp backup file {}: {}", tempFile, ex.getMessage());
            }
        }
    }

    private void dumpAndCompress(Path destination) throws IOException, InterruptedException {
        BackupProperties.Postgres pg = props.getPostgres();
        ProcessBuilder pb = new ProcessBuilder(
                props.getPgDumpPath(),
                "-h", pg.getHost(),
                "-p", String.valueOf(pg.getPort()),
                "-U", pg.getUsername(),
                "-d", pg.getDatabase(),
                "--no-password"
        );
        pb.environment().put("PGPASSWORD", pg.getPassword() != null ? pg.getPassword() : "");

        Process process = pb.start();

        // Read stderr in a background thread to prevent pipe-buffer deadlock
        StringBuilder stderr = new StringBuilder();
        Thread stderrReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append('\n');
                }
            } catch (IOException ignored) {
            }
        });
        stderrReader.start();

        try (InputStream pgOut = process.getInputStream();
             GZIPOutputStream gz = new GZIPOutputStream(
                     new FileOutputStream(destination.toFile()))) {
            pgOut.transferTo(gz);
        }

        int exit = process.waitFor();
        stderrReader.join();

        if (exit != 0) {
            throw new IOException("pg_dump exited with code " + exit + ": " + stderr);
        }
    }

    private void uploadToDrive(Path file, String filename) throws IOException {
        File metadata = new File();
        metadata.setName(filename);
        metadata.setParents(Collections.singletonList(props.getDriveFolderId()));

        FileContent content = new FileContent("application/gzip", file.toFile());
        googleDrive.files().create(metadata, content)
                .setFields("id,name")
                .execute();
    }

    private void pruneOldBackups() throws IOException {
        String query = String.format(
                "'%s' in parents and name contains 'schooldb-' and trashed = false",
                props.getDriveFolderId()
        );
        FileList result = googleDrive.files().list()
                .setQ(query)
                .setFields("files(id,name,createdTime)")
                .setOrderBy("createdTime desc")
                .execute();

        List<File> files = result.getFiles();
        if (files == null) return;

        LocalDate cutoff = LocalDate.now().minusDays(props.getRetentionDays());
        for (File f : files) {
            if (f.getCreatedTime() == null) continue;
            LocalDate created = LocalDate.ofEpochDay(f.getCreatedTime().getValue() / 86_400_000L);
            if (created.isBefore(cutoff)) {
                googleDrive.files().delete(f.getId()).execute();
                log.info("Pruned old backup: {}", f.getName());
            }
        }
    }
}
