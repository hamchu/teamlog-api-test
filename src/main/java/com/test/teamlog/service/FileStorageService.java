package com.test.teamlog.service;

import com.test.teamlog.config.FileConfig;
import com.test.teamlog.entity.Post;
import com.test.teamlog.entity.PostMedia;
import com.test.teamlog.exception.FileStorageException;
import com.test.teamlog.exception.MyFileNotFoundException;
import com.test.teamlog.exception.ResourceNotFoundException;
import com.test.teamlog.payload.FileDTO;
import com.test.teamlog.repository.PostMediaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class FileStorageService {
    private final PostMediaRepository postMediaRepository;
    private final Path fileStorageLocation;

    @Autowired
    public FileStorageService(PostMediaRepository postMediaRepository, FileConfig fileStorageProperties) {
        this.postMediaRepository = postMediaRepository;
        this.fileStorageLocation = Paths.get(fileStorageProperties.getUploadDir())
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.fileStorageLocation);
        } catch (Exception ex) {
            throw new FileStorageException("Could not create the directory where the uploaded files will be stored.", ex);
        }
    }

    @Transactional
    public String storeFile(MultipartFile file, Post post, Boolean isMedia) {
        String originalFileName = StringUtils.cleanPath(file.getOriginalFilename());
        String fileExtension = originalFileName.substring(originalFileName.lastIndexOf("."));
        UUID uuid = UUID.randomUUID();
        String storedFileName = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "(" + uuid.toString() + ")" + fileExtension;
        try {
            // Check invalid characters
            if (originalFileName.contains("..")) {
                throw new FileStorageException("Sorry! Filename contains invalid path sequence " + originalFileName);
            }

            // Copy file to the target location
            Path targetLocation = this.fileStorageLocation.resolve(storedFileName);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new FileStorageException("Could not store file " + originalFileName + ". Please try again!", ex);
        }
        if (post != null) {
            PostMedia newPostMedia = PostMedia.builder()
                    .fileName(originalFileName) // Normalize file name
                    .storedFileName(storedFileName)
                    .contentType(file.getContentType())
                    .post(post)
                    .isMedia(isMedia)
                    .build();

            postMediaRepository.save(newPostMedia);
        }

        return storedFileName;
    }

    public FileDTO.FileResourceInfo loadFileAsFileResource(String storedFileName) {
        PostMedia media = postMediaRepository.findByStoredFileName(storedFileName);

        try {
            Path filePath = this.fileStorageLocation.resolve(storedFileName).normalize();
            Resource resource = new UrlResource(filePath.toUri());
            if (resource.exists()) {
                if (media == null) {
                    FileDTO.FileResourceInfo fileResource = FileDTO.FileResourceInfo.builder()
                            .fileName(storedFileName)
                            .contentType("image/jpeg")
                            .resource(resource)
                            .build();
                    return fileResource;
                }
                FileDTO.FileResourceInfo fileResource = FileDTO.FileResourceInfo.builder()
                        .fileName(media.getFileName())
                        .contentType(media.getContentType())
                        .resource(resource)
                        .build();
                return fileResource;
            } else {
                throw new MyFileNotFoundException("File not found " + storedFileName);
            }
        } catch (MalformedURLException ex) {
            throw new MyFileNotFoundException("File not found " + storedFileName, ex);
        }
    }

    @Transactional
    public void deleteFilesByPost(Post post) {
        List<PostMedia> mediaFiles = postMediaRepository.findAllByPost(post);

        for (PostMedia media : mediaFiles) {
            deleteFile(media.getStoredFileName());
            postMediaRepository.delete(media);
        }
    }

    @Transactional
    public void deleteFileById(Long id) {
        PostMedia media = postMediaRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PostMedia", "id", id));
        postMediaRepository.delete(media);
    }

    public void deleteFile(String storedFileName) {
        Path mediaPath = this.fileStorageLocation.resolve(storedFileName);
        File file = new File(mediaPath.toString());
        if (file.exists()) {
            if (file.delete()) {
                System.out.println("파일-" + storedFileName + " 삭제 성공");
            }
        } else throw new MyFileNotFoundException("File not found " + storedFileName);
    }
}
