package com.blog.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class FileUtil {

    public static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            log.error("Error reading file: {}", path, e);
            throw new RuntimeException("Failed to read file: " + path, e);
        }
    }

    public static List<Path> findFiles(String directory, String pattern) {
        Path dirPath = Paths.get(directory);
        if (!Files.exists(dirPath)) {
            log.warn("Directory does not exist: {}", directory);
            return new ArrayList<>();
        }

        try (Stream<Path> paths = Files.walk(dirPath)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .filter(path -> !path.toString().contains("_drafts"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Error walking directory: {}", directory, e);
            return new ArrayList<>();
        }
    }

    public static String extractCategory(Path path, String articlesPath) {
        Path relativePath = articlesPath.isEmpty()
                ? path
                : Paths.get(articlesPath).relativize(path);

        if (relativePath.getNameCount() > 1) {
            return relativePath.getName(0).toString();
        }
        return "默认";
    }

    public static String generateId(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.replace(".md", "");
    }

    public static boolean isPublished(String markdown) {
        if (!markdown.contains("---")) {
            return true; // Default to published if no front matter
        }

        String[] lines = markdown.split("\n");
        boolean inFrontMatter = false;

        for (String line : lines) {
            if (line.trim().equals("---")) {
                if (!inFrontMatter) {
                    inFrontMatter = true;
                    continue;
                } else {
                    break;
                }
            }
            if (inFrontMatter && line.trim().startsWith("published:")) {
                String value = line.substring(line.indexOf(":") + 1).trim();
                return Boolean.parseBoolean(value);
            }
        }

        return true; // Default to published if not specified
    }
}
