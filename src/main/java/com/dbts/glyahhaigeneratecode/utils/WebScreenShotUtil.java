package com.dbts.glyahhaigeneratecode.utils;

import cn.hutool.core.img.ImgUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import io.github.bonigarcia.wdm.WebDriverManager;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.time.Duration;
import java.util.UUID;

/**
 *
 */
@Slf4j
public class WebScreenShotUtil {

    private static final WebDriver webDriver;

    /**
     * 初始化webDriver
     *
     * @param width  屏幕宽度
     * @param height 屏幕高度
     * @return WebDriver
     */
    static {
        int DEFAULT_WITH = 1600;
        int DEFAULT_HEIGHT = 1080;
        webDriver = initWebDriver(DEFAULT_WITH, DEFAULT_HEIGHT);
    }


    /**
     * 生成网页截图
     *
     * @param webUrl 网页URL
     * @return 压缩后的截图文件路径，失败返回null
     */
    public static String saveWebPageScreenshot(String webUrl) {
        if (StrUtil.isBlank(webUrl)) {
            log.error("网页URL不能为空");
            return null;
        }
        try {
            String targetUrl = resolveToIndexHtmlUrl(webUrl);
            // 创建临时目录  eg: temp\screenshots\12345678
            String rootPath = System.getProperty("user.dir") + File.separator + "temp" + File.separator + "screenshots"
                    + File.separator + UUID.randomUUID().toString().substring(0, 8);
            FileUtil.mkdir(rootPath);
            // 图片后缀
            final String IMAGE_SUFFIX = ".png";
            // 原始截图文件路径
            String imageSavePath = rootPath + File.separator + RandomUtil.randomNumbers(5) + IMAGE_SUFFIX;
            // 访问网页 自带
            // 若传入的是本地部署目录，则自动进入对应 index.html，避免截到目录/空白页
            if (!targetUrl.equals(webUrl)) {
                log.info("本地路径解析为截图入口：{} -> {}", webUrl, targetUrl);
            }
            webDriver.get(targetUrl);
            // 等待页面加载完成
            waitForPageLoad(webDriver);
            // 截图 自带
            byte[] screenshotBytes = ((TakesScreenshot) webDriver).getScreenshotAs(OutputType.BYTES);
            // 保存原始图片
            saveImage(screenshotBytes, imageSavePath);
            log.info("原始截图保存成功: {}", imageSavePath);

            // 压缩图片
            final String COMPRESSION_SUFFIX = "_compressed.jpg";
            String compressedImagePath = rootPath + File.separator + RandomUtil.randomNumbers(5) + COMPRESSION_SUFFIX;

            compressImage(imageSavePath, compressedImagePath);
            log.info("压缩图片保存成功: {}", compressedImagePath);
            // 删除原始图片，只保留压缩图片
            FileUtil.del(imageSavePath);
            return compressedImagePath;
        } catch (Exception e) {
            log.error("网页截图失败: {}", webUrl, e);
            return null;
        }
    }

    /**
     * 将“本地目录路径”自动解析为 “file:///.../index.html”。
     * 若传入的是 http(s)/file URL，则原样返回。
     */
    private static String resolveToIndexHtmlUrl(String webUrl) {
        String trimmed = webUrl.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("file://")) {
            return trimmed;
        }

        File local = new File(trimmed);
        if (local.isDirectory()) {
            local = new File(local, "index.html");
        }
        if (!local.exists() || !local.isFile()) {
            log.warn("本地截图入口不存在：{}（将尝试直接访问原始参数）", local.getAbsolutePath());
            return trimmed;
        }
        // toURI 自动生成 file:///D:/xxx 这种可被 Chrome 正确解析的地址
        return local.toURI().toString();
    }







    /**
     * 当Bean销毁时，关闭webDriver
     */
    @PreDestroy
    public static void destroy() {
        webDriver.quit();
    }


    /**
     * 初始化 Chrome 浏览器驱动
     */
    private static WebDriver initWebDriver(int width, int height) {
        try {
            // 自动管理 ChromeDriver
            WebDriverManager.chromedriver().setup();
            // 配置 Chrome 选项
            ChromeOptions options = new ChromeOptions();
            // 无头模式
            options.addArguments("--headless");
            // 禁用GPU（在某些环境下避免问题）
            options.addArguments("--disable-gpu");
            // 禁用沙盒模式（Docker环境需要）
            options.addArguments("--no-sandbox");
            // 禁用开发者shm使用
            options.addArguments("--disable-dev-shm-usage");
            // 允许 file:// 下的模块/资源加载（否则常见表现是：页面保持空白）
            options.addArguments("--allow-file-access-from-files");
            options.addArguments("--disable-web-security");
            options.addArguments("--remote-allow-origins=*");
            // 避免站点隔离导致 file:// 相关请求被限制
            options.addArguments("--disable-site-isolation-trials");
            // 设置窗口大小
            options.addArguments(String.format("--window-size=%d,%d", width, height));
            // 禁用扩展
            options.addArguments("--disable-extensions");
            // 设置用户代理
            options.addArguments("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            // 创建驱动
            WebDriver driver = new ChromeDriver(options);
            // 设置页面加载超时
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
            // 设置隐式等待
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
            return driver;
        } catch (Exception e) {
            log.error("初始化 Chrome 浏览器失败", e);
            throw new MyException(ErrorCode.SYSTEM_ERROR, "初始化 Chrome 浏览器失败");
        }
    }


    /**
     * 保存图片到文件
     */
    private static void saveImage(byte[] imageBytes, String path) {
        try {
            FileUtil.writeBytes(imageBytes, path);
        } catch (Exception e) {
            log.error("保存图片失败, 路径:{}", path);
            throw new MyException(ErrorCode.SYSTEM_ERROR, "保存图片失败");
        }
    }


    /**
     * 压缩图片
     */
    private static void compressImage(String originalImagePath, String compressedImagePath) {
        // 压缩图片质量（0.1 = 10% 质量）
        final float COMPRESSION_QUALITY = 0.3f;
        try {
            ImgUtil.compress(
                    FileUtil.file(originalImagePath),
                    FileUtil.file(compressedImagePath),
                    COMPRESSION_QUALITY
            );
        } catch (Exception e) {
            log.error("压缩图片失败: {} -> {}", originalImagePath, compressedImagePath, e);
            throw new MyException(ErrorCode.SYSTEM_ERROR, "压缩图片失败");
        }
    }


    /**
     * 等待页面加载完成
     */
    private static void waitForPageLoad(WebDriver driver) {
        try {
            // 创建等待页面加载对象
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            // 等待 document.readyState 为complete
            wait.until(webDriver ->
                    ((JavascriptExecutor) webDriver)
                            .executeScript("return document.readyState")
                            .equals("complete")
            );
            // 先等待 #app 出现明显内容；若失败则兜底等待后截图，保证不会因为条件不满足而中断。
            try {
                WebDriverWait renderedWait = new WebDriverWait(driver, Duration.ofSeconds(12));
                renderedWait.until(webDriver -> {
                    Object ok = ((JavascriptExecutor) webDriver).executeScript(
                            "(() => {" +
                                    " const el=document.querySelector('#app');" +
                                    " if(!el) return false;" +
                                    " const text=(el.innerText||'').trim();" +
                                    " const html=(el.innerHTML||'').trim();" +
                                    " return (text.length>40) || (html.length>400);" +
                                    "})()"
                    );
                    return ok instanceof Boolean && (Boolean) ok;
                });
            } catch (Exception ignore) {
                // 条件不满足不阻塞截图
            }
            Thread.sleep(3000);
            log.info("页面加载完成");
        } catch (Exception e) {
            log.error("等待页面加载时出现异常，继续执行截图", e);
        }
    }

}

