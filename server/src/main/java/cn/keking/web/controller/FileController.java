package cn.keking.web.controller;

import cn.keking.config.ConfigConstants;
import cn.keking.model.ReturnResponse;
import cn.keking.utils.CaptchaUtil;
import cn.keking.utils.DateUtils;
import cn.keking.utils.KkFileUtils;
import cn.keking.utils.RarUtils;
import cn.keking.utils.WebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.keking.utils.CaptchaUtil.CAPTCHA_CODE;
import static cn.keking.utils.CaptchaUtil.CAPTCHA_GENERATE_TIME;

/**
 * @author yudian-it
 * 2017/12/1
 */
@RestController
public class FileController {

    private final Logger logger = LoggerFactory.getLogger(FileController.class);

    private final String fileDir = ConfigConstants.getFileDir();
    private final String demoDir = "demo";

    private final String demoPath = demoDir + File.separator;
    public static final String BASE64_DECODE_ERROR_MSG = "Base64解码失败，请检查你的 %s 是否采用 Base64 + urlEncode 双重编码了！";

    @PostMapping("/fileUpload")
    public ReturnResponse<Object> fileUpload(@RequestParam("file") MultipartFile file, String path) {
        String relativePath = sanitizeRelativePath(path);
        if (relativePath == null) {
            return ReturnResponse.failure("非法上传路径！");
        }
        ReturnResponse<Object> checkResult = this.fileUploadCheck(file, relativePath);
        if (checkResult.isFailure()) {
            return checkResult;
        }
        File outFile = new File(fileDir + demoPath + relativePath);
        if (!outFile.exists() && !outFile.mkdirs()) {
            logger.error("创建文件夹【{}】失败，请检查目录权限！", outFile.getPath());
        }
        String fileName = checkResult.getContent().toString();
        String fullPath = fileDir + demoPath + relativePath + (relativePath.isEmpty() ? "" : "/") + fileName;
        logger.info("上传文件：{}", fullPath);
        try (InputStream in = file.getInputStream(); OutputStream out = Files.newOutputStream(Paths.get(fullPath))) {
            StreamUtils.copy(in, out);
            return ReturnResponse.success(null);
        } catch (IOException e) {
            logger.error("文件上传失败", e);
            return ReturnResponse.failure();
        }
    }

    @GetMapping("/deleteFile")
    public ReturnResponse<Object> deleteFile(HttpServletRequest request, String fileName, String password) {
        ReturnResponse<Object> checkResult = this.deleteFileCheck(request, fileName, password);
        if (checkResult.isFailure()) {
            return checkResult;
        }
        fileName = checkResult.getContent().toString();
        File file = new File(fileDir + demoPath + fileName);
        logger.info("删除文件/文件夹：{}", file.getAbsolutePath());
        if (!file.exists()) {
            return ReturnResponse.failure("文件或文件夹不存在");
        }
        boolean deleted = file.isDirectory() ? deleteDirectory(file) : file.delete();
        if (!deleted) {
            String msg = String.format("删除【%s】失败，请检查目录权限！", file.getPath());
            logger.error(msg);
            return ReturnResponse.failure(msg);
        }
        WebUtils.removeSessionAttr(request, CAPTCHA_CODE); //删除缓存验证码
        return ReturnResponse.success();
    }

    /**
     * 递归删除目录
     */
    private boolean deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteDirectory(child)) {
                        return false;
                    }
                }
            }
        }
        return dir.delete();
    }

    /**
     * 验证码方法
     */
    @RequestMapping("/deleteFile/captcha")
    public void captcha(HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (!ConfigConstants.getDeleteCaptcha()) {
            return;
        }

        response.setContentType("image/jpeg");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Cache-Control", "no-cache");
        response.setDateHeader("Expires", -1);
        String captchaCode = WebUtils.getSessionAttr(request, CAPTCHA_CODE);
        long captchaGenerateTime = WebUtils.getLongSessionAttr(request, CAPTCHA_GENERATE_TIME);
        long timeDifference = DateUtils.calculateCurrentTimeDifference(captchaGenerateTime);

        // 验证码为空，且生成验证码超过50秒，重新生成验证码
        if (timeDifference > 50 && ObjectUtils.isEmpty(captchaCode)) {
            captchaCode = CaptchaUtil.generateCaptchaCode();
            // 更新验证码
            WebUtils.setSessionAttr(request, CAPTCHA_CODE, captchaCode);
            WebUtils.setSessionAttr(request, CAPTCHA_GENERATE_TIME, DateUtils.getCurrentSecond());
        } else {
            captchaCode = ObjectUtils.isEmpty(captchaCode) ? "wait" : captchaCode;
        }

        ServletOutputStream outputStream = response.getOutputStream();
        ImageIO.write(CaptchaUtil.generateCaptchaPic(captchaCode), "jpeg", outputStream);
        outputStream.close();
    }

    @PostMapping("/listFiles")
    public Map<String, Object> getFiles(@RequestParam(value = "path", defaultValue = "") String path,
                                        @RequestParam(value = "searchText", defaultValue = "") String searchText,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size,
                                        @RequestParam(required = false) String sort,
                                        @RequestParam(required = false) String order) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> data = new ArrayList<>();
        result.put("total", 0);
        result.put("data", data);

        String relativePath = sanitizeRelativePath(path);
        if (relativePath == null) {
            result.put("error", "非法目录路径");
            return result;
        }
        if (page < 0) {
            page = 0;
        }
        if (size <= 0) {
            size = 20;
        }
        if (size > 100) {
            size = 100;
        }

        File dir = new File(fileDir + demoPath + relativePath);
        if (!dir.isDirectory()) {
            return result;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return result;
        }

        // 搜索过滤
        List<File> matchedFiles = new ArrayList<>();
        String searchLower = (searchText == null || searchText.trim().isEmpty())
                ? null : searchText.trim().toLowerCase();
        for (File file : files) {
            if (searchLower != null && !file.getName().toLowerCase().contains(searchLower)) {
                continue;
            }
            matchedFiles.add(file);
        }

        sortFiles(matchedFiles, sort, order);

        // 分页
        int total = matchedFiles.size();
        int start = page * size;
        int end = Math.min(start + size, total);
        for (int i = start; i < end; i++) {
            data.add(convertFileInfoToMap(matchedFiles.get(i), relativePath));
        }
        result.put("total", total);
        return result;
    }

    @PostMapping("/createFolder")
    public ReturnResponse<Object> createFolder(String path, String folderName) {
        if (ObjectUtils.isEmpty(folderName) || folderName.contains("/") || folderName.contains("\\")
                || KkFileUtils.isIllegalFileName(folderName)) {
            return ReturnResponse.failure("非法文件夹名称！");
        }
        String relativePath = sanitizeRelativePath(path);
        if (relativePath == null) {
            return ReturnResponse.failure("非法路径！");
        }
        File folder = new File(fileDir + demoPath + relativePath
                + (relativePath.isEmpty() ? "" : "/") + folderName);
        if (folder.exists()) {
            return ReturnResponse.failure("文件夹已存在！");
        }
        if (folder.mkdirs()) {
            return ReturnResponse.success();
        }
        logger.error("创建文件夹【{}】失败，请检查目录权限！", folder.getPath());
        return ReturnResponse.failure("创建文件夹失败，请检查目录权限！");
    }

    /**
     * 文件列表排序：文件夹始终优先，再按指定字段与方向排序
     * 默认（无 sort 参数）：按修改时间降序
     */
    private void sortFiles(List<File> files, String sort, String order) {
        boolean asc = !"desc".equalsIgnoreCase(order);
        Comparator<File> fieldComparator;
        if (sort == null || sort.trim().isEmpty()) {
            fieldComparator = Comparator.comparingLong(File::lastModified);
            asc = false;
        } else {
            switch (sort.trim().toLowerCase()) {
                case "name":
                    fieldComparator = Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER);
                    break;
                case "lastmodified":
                    fieldComparator = Comparator.comparingLong(File::lastModified);
                    break;
                case "size":
                    fieldComparator = Comparator.comparingLong(File::length);
                    break;
                case "isdirectory":
                    fieldComparator = Comparator.comparingLong(File::lastModified);
                    asc = false;
                    break;
                default:
                    fieldComparator = Comparator.comparingLong(File::lastModified);
                    asc = false;
                    break;
            }
        }
        Comparator<File> finalComparator = Comparator.comparing(File::isDirectory).reversed()
                .thenComparing(asc ? fieldComparator : fieldComparator.reversed());
        files.sort(finalComparator);
    }

    /**
     * 将文件信息转换为前端表格需要的格式
     */
    private Map<String, Object> convertFileInfoToMap(File file, String path) {
        Map<String, Object> fileMap = new HashMap<>();
        fileMap.put("name", file.getName());
        fileMap.put("isDirectory", file.isDirectory());
        fileMap.put("lastModified", file.lastModified());
        fileMap.put("size", file.length());
        // 用于构建预览 URL
        fileMap.put("relativePath", demoDir + "/" + (path.isEmpty() ? "" : path + "/") + file.getName());
        // 目录的相对 demo 根路径，用于目录导航
        if (file.isDirectory()) {
            fileMap.put("fullPath", path.isEmpty() ? file.getName() : path + "/" + file.getName());
        }
        return fileMap;
    }

    /**
     * 校验相对 demo 根目录的子路径，拒绝绝对路径、目录逃逸与非法路径段
     *
     * @return 规范化后的相对路径（空串表示根目录）；null 表示路径非法
     */
    private String sanitizeRelativePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "";
        }
        String normalized = path.replace('\\', '/').trim();
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            return null;
        }
        String[] segments = normalized.split("/");
        List<String> validSegments = new ArrayList<>();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || KkFileUtils.isIllegalFileName(segment)) {
                return null;
            }
            validSegments.add(segment);
        }
        return String.join("/", validSegments);
    }

    /**
     * 上传文件前校验
     *
     * @param file 文件
     * @return 校验结果
     */
    private ReturnResponse<Object> fileUploadCheck(MultipartFile file, String relativePath) {
        if (ConfigConstants.getFileUploadDisable()) {
            return ReturnResponse.failure("文件传接口已禁用");
        }
        String fileName = WebUtils.getFileNameFromMultipartFile(file);
        if (fileName.lastIndexOf(".") == -1) {
            return ReturnResponse.failure("不允许上传的类型");
        }
        if (!KkFileUtils.isAllowedUpload(fileName)) {
            return ReturnResponse.failure("不允许上传的文件类型: " + fileName);
        }
        if (KkFileUtils.isIllegalFileName(fileName)) {
            return ReturnResponse.failure("不允许上传的文件名: " + fileName);
        }
        // 判断是否存在同名文件
        if (existsFile(fileName, relativePath)) {
            return ReturnResponse.failure("存在同名文件，请先删除原有文件再次上传");
        }
        return ReturnResponse.success(fileName);
    }


    /**
     * 删除文件前校验
     *
     * @param fileName 文件名
     * @return 校验结果
     */
    private ReturnResponse<Object> deleteFileCheck(HttpServletRequest request, String fileName, String password) {
        if (ObjectUtils.isEmpty(fileName)) {
            return ReturnResponse.failure("文件名为空，删除失败！");
        }
        try {
            fileName = WebUtils.decodeUrl(fileName);
        } catch (Exception ex) {
            String errorMsg = String.format(BASE64_DECODE_ERROR_MSG, fileName);
            return ReturnResponse.failure(errorMsg + "删除失败！");
        }
        if (fileName == null) {
            return ReturnResponse.failure("文件名为空，删除失败！");
        }
        // 前端传参为 Base64("http://" + 路径)，从中提取路径部分
        if (fileName.contains("://")) {
            int pathStart = fileName.indexOf('/', fileName.indexOf("://") + 3);
            fileName = pathStart >= 0 ? fileName.substring(pathStart + 1)
                    : fileName.substring(fileName.indexOf("://") + 3);
        }
        // relativePath 格式带 demo 前缀，fullPath（目录导航）与纯文件名不带，统一为相对 demo 根的路径
        if (fileName.startsWith(demoDir + "/")) {
            fileName = fileName.substring(demoDir.length() + 1);
        }
        // 支持子目录路径（新版首页目录导航），并拒绝目录逃逸
        String relativePath = sanitizeRelativePath(fileName);
        if (relativePath == null) {
            return ReturnResponse.failure("非法文件名，删除失败！");
        }
        fileName = relativePath;
        if (ObjectUtils.isEmpty(password)) {
            return ReturnResponse.failure("密码 or 验证码为空，删除失败！");
        }

        String expectedPassword = ConfigConstants.getDeleteCaptcha() ? WebUtils.getSessionAttr(request, CAPTCHA_CODE) : ConfigConstants.getPassword();

        if (!password.equalsIgnoreCase(expectedPassword)) {
            logger.error("删除文件【{}】失败，密码错误！", fileName);
            return ReturnResponse.failure("删除文件失败，密码错误！");
        }
        return ReturnResponse.success(fileName);
    }

    @GetMapping("/directory")
    public Object directory(String urls) {
        String fileUrl;
        try {
            fileUrl = WebUtils.decodeUrl(urls);
        } catch (Exception ex) {
            String errorMsg = String.format(BASE64_DECODE_ERROR_MSG, "url");
            return ReturnResponse.failure(errorMsg);
        }
        fileUrl = fileUrl.replaceAll("http://", "");
        if (KkFileUtils.isIllegalFileName(fileUrl)) {
            return ReturnResponse.failure("不允许访问的路径:");
        }
        return RarUtils.getTree(fileUrl);
    }

    private boolean existsFile(String fileName, String relativePath) {
        File file = new File(fileDir + demoPath
                + (relativePath == null || relativePath.isEmpty() ? "" : relativePath + "/") + fileName);
        return file.exists();
    }
}
