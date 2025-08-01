package cn.iocoder.yudao.module.digitalcourse.util;


import cn.iocoder.yudao.module.infra.api.config.ConfigApi;
import cn.iocoder.yudao.module.infra.api.file.FileApi;
import com.alibaba.fastjson.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Component;


@Component
@Slf4j
public class Pdf2MdUtil {
    private static final String BASE_URL = "doc2x.url";
    private static final String API_KEY = "doc2x.key";

    @Resource
    private FileApi fileApi;
    @Resource
    private ConfigApi configApi;

    public String recognizeMarkdown(String fileUrl, String type) throws IOException, InterruptedException {
        if (fileUrl == null || fileUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("fileUrl不能为空");
        }
        fileUrl = fileUrl.trim();
        
        if (fileUrl.endsWith(".txt")) {
            // Handle txt files directly
            return new String(Files.readAllBytes(Paths.get(new URL(fileUrl).getPath())));
        } else if (fileUrl.endsWith(".docx")) {
            if ("text".equalsIgnoreCase(type)) {
                // Extract text directly from Word document
                return extractTextFromWord(fileUrl);
            } else {
                // Convert Word document to PDF
                fileUrl = convertWordToPdf(fileUrl);
            }
        }

        // Step 1: Submit recognition task
        String uuid = submitRecognitionTask(fileUrl);
        if (uuid == null) {
            throw new IOException("提交识别任务失败。");
        }

        // Step 2: Poll task status until it's completed
        String content = pollTaskStatus(uuid, type);
        if (content == null) {
            throw new IOException("获取识别内容失败。");
        }

        return content;
    }

    private String submitRecognitionTask(String fileUrl) throws IOException {
        // 尝试不同的API端点格式
        String endpoint;
        if (fileUrl.endsWith(".pdf")) {
            endpoint = "/api/v1/async/pdf";
            // 如果第一个端点失败，尝试其他可能的端点
            // endpoint = "/api/pdf/async";
            // endpoint = "/v1/async/pdf";
        } else {
            endpoint = "/api/v1/async/img";
            // endpoint = "/api/img/async";
            // endpoint = "/v1/async/img";
        }
        
        String baseUrl = configApi.getConfigValueByKey(BASE_URL);
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IOException("doc2x.url 配置未设置或为空");
        }
        baseUrl = baseUrl.trim();
        String url = baseUrl + endpoint;

        Map<String, Object> param = new HashMap<String, Object>();
        param.put("pdf_url", fileUrl);
        param.put("ocr", "true");
        Map<String, String> header = new HashMap<>();

        String apiKey = configApi.getConfigValueByKey(API_KEY);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IOException("doc2x.key 配置未设置或为空");
        }
        header.put("Authorization", "Bearer " + apiKey.trim());

        // 添加详细日志
        log.info("PDF转Markdown请求 - URL: {}, 参数: {}, 文件: {}", url, param, fileUrl);

        HttpUtils.HttpResponse response = HttpUtils.postForm(url, param, header);
        
        // 添加响应日志
        log.info("PDF转Markdown响应 - 状态码: {}, 响应内容: {}", response.getStatus(), response.getResponse());
        
        if (response.getStatus() != 200) {
            // 如果是404错误，尝试其他可能的端点
            if (response.getStatus() == 404) {
                log.warn("API端点 {} 返回404，尝试备用端点", endpoint);
                
                // 尝试备用端点
                String backupEndpoint = fileUrl.endsWith(".pdf") ? "/api/pdf/async" : "/api/img/async";
                String backupUrl = baseUrl + backupEndpoint;
                log.info("尝试备用端点: {}", backupUrl);
                
                HttpUtils.HttpResponse backupResponse = HttpUtils.postForm(backupUrl, param, header);
                log.info("备用端点响应 - 状态码: {}, 响应内容: {}", backupResponse.getStatus(), backupResponse.getResponse());
                
                if (backupResponse.getStatus() == 200) {
                    JSONObject jsonResponse = backupResponse.getResponseToJson();
                    return jsonResponse.getJSONObject("data").getString("uuid");
                }
            }
            
            throw new RuntimeException("提交识别任务失败，http状态码=" + response.getStatus() + 
                ", 响应内容=" + response.getResponse() + ", 请求URL=" + url);
        }

        JSONObject jsonResponse = response.getResponseToJson();
        return jsonResponse.getJSONObject("data").getString("uuid");
    }

    private String pollTaskStatus(String uuid, String type) throws IOException, InterruptedException {
        String status;
        StringBuilder content = new StringBuilder();
        long startTime = System.currentTimeMillis();
        long timeout = 60 * 1000; // 1 minute timeout

        do {
            if (System.currentTimeMillis() - startTime > timeout) {
                throw new IOException("任务轮询超时，超过1分钟。");
            }

            try {
                Thread.sleep(1000); // Wait for 1 second before polling again
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("轮询被中断。");
            }

            String baseUrl = configApi.getConfigValueByKey(BASE_URL);
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                throw new IOException("doc2x.url 配置未设置或为空");
            }
            baseUrl = baseUrl.trim();
            String url = baseUrl + "/api/v1/async/status?uuid=" + uuid;

            String apiKey = configApi.getConfigValueByKey(API_KEY);
            if (apiKey == null || apiKey.trim().isEmpty()) {
                throw new IOException("doc2x.key 配置未设置或为空");
            }

            HttpUtils.HttpRequest httpRequest = HttpUtils.HttpRequest.get(url);
            httpRequest.addHeaders("Authorization", "Bearer " + apiKey.trim());

            HttpUtils.HttpResponse response = HttpUtils.request(httpRequest);
            if (response.getStatus() != 200) {
                throw new IOException("获取任务状态失败，http状态码=" + response.getStatus());
            }

            JSONObject jsonResponse = response.getResponseToJson();
            status = jsonResponse.getJSONObject("data").getString("status");
            if ("success".equals(status)) {
                jsonResponse.getJSONObject("data").getJSONObject("result").getJSONArray("pages").forEach(page -> {
                    JSONObject pageObject = (JSONObject) page;
                    if ("markdown".equalsIgnoreCase(type)) {
                        content.append(pageObject.getString("md")).append("\n");
                    } else if ("text".equalsIgnoreCase(type)) {
                        String markdown = pageObject.getString("md");
                        Parser parser = Parser.builder().build();
                        Node document = parser.parse(markdown);
                        TextContentRenderer renderer = TextContentRenderer.builder().build();
                        content.append(renderer.render(document)).append("\n");
                    }
                });
            }
        } while (!"success".equals(status));

        return content.toString();
    }

    private String convertWordToPdf(String wordFileUrl) throws IOException {
        // Load Word document
        try (InputStream is = new URL(wordFileUrl).openStream();
             XWPFDocument doc = new XWPFDocument(is);
             PDDocument pdfDoc = new PDDocument()) {
            PDPage page = new PDPage();
            pdfDoc.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(pdfDoc, page)) {
                doc.getParagraphs().forEach(paragraph -> {
                    try {
                        contentStream.beginText();
                        contentStream.setFont(PDType1Font.HELVETICA, 12);
                        contentStream.newLineAtOffset(50, 750);
                        contentStream.showText(paragraph.getText());
                        contentStream.endText();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }

            // Save PDF to a temporary file
            File tempFile = File.createTempFile("converted", ".pdf");
            pdfDoc.save(tempFile);

            // Upload PDF to OSS and get the URL
            String pdfUrl = uploadFile(tempFile.getName(), tempFile);
            return pdfUrl;
        }
    }

    /**
     * 上传文件到文件服务
     * @param fileName 文件名称，带有 .vtt 扩展名
     * @param file 要上传的文件
     * @return 上传后文件的 URL
     * @throws IOException 文件读取异常
     */
    private String uploadFile(String fileName, File file) throws IOException {
        try {
            byte[] fileContent = Files.readAllBytes(file.toPath());
            // 指定文件路径（你可以根据实际情况设置）
            String filePath = "uploads/vtt/" + fileName;
            // 上传文件到文件服务
            String fileUrl = fileApi.createFile(fileName, filePath, fileContent);
            System.out.println("File uploaded successfully. File URL: " + fileUrl);
            return fileUrl;
        } catch (Exception e) {
            System.err.println("Error uploading file: " + e.getMessage());
            throw new IOException("Failed to upload file", e);
        }
    }

    private String extractTextFromWord(String wordFileUrl) throws IOException {
        try (InputStream is = new URL(wordFileUrl).openStream();
             XWPFDocument doc = new XWPFDocument(is)) {
            StringBuilder text = new StringBuilder();
            doc.getParagraphs().forEach(paragraph -> text.append(paragraph.getText()).append("\n"));
            return text.toString();
        }
    }

    public static void main(String[] args) {
        Pdf2MdUtil service = new Pdf2MdUtil();
        try {
            String markdown = service.recognizeMarkdown("https://easegenedu.obs.cn-north-9.myhuaweicloud.com/数字化管理师-第一章-第一节.pdf", "text");
            System.out.println("Recognized Markdown:\n" + markdown);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}
