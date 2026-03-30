package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.service.AppService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.io.File;

/**
 * 部署静态资源访问：读取 temp/code_deploy/{deployKey}。
 */
@RestController
@RequestMapping("/deploy")
@RequiredArgsConstructor
public class DeployResourceController {

    private AppService appService;

    @GetMapping("/{deployKey}/**")
    public ResponseEntity<Resource> serveDeployResource(@PathVariable String deployKey, HttpServletRequest request) {
        try {
            String resourcePath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
            resourcePath = resourcePath.substring(("/deploy/" + deployKey).length());

            // debug 修复：目录访问时补尾斜杠，避免相对资源路径解析偏移。
            if (resourcePath.isEmpty()) {
                HttpHeaders headers = new HttpHeaders();
                headers.add("Location", request.getRequestURI() + "/");
                return new ResponseEntity<>(headers, HttpStatus.MOVED_PERMANENTLY);
            }
            if ("/".equals(resourcePath)) {
                resourcePath = "/index.html";
            }

            String filePath = AppConstant.CODE_DEPLOY_ROOT_DIR + "/" + deployKey + resourcePath;
            File file = new File(filePath);
            if (!file.exists()) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new FileSystemResource(file);

            return ResponseEntity.ok()
                    .header("Content-Type", getContentTypeWithCharset(filePath))
                    .body(resource);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private String getContentTypeWithCharset(String filePath) {
        if (filePath.endsWith(".html")) return "text/html; charset=UTF-8";
        if (filePath.endsWith(".css")) return "text/css; charset=UTF-8";
        if (filePath.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (filePath.endsWith(".png")) return "image/png";
        if (filePath.endsWith(".jpg") || filePath.endsWith(".jpeg")) return "image/jpeg";
        if (filePath.endsWith(".svg")) return "image/svg+xml";
        if (filePath.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }
}
