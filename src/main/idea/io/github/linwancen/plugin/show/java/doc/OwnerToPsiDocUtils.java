package io.github.linwancen.plugin.show.java.doc;

import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassOwner;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiDocCommentOwner;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.javadoc.PsiDocComment;
import io.github.linwancen.plugin.show.bean.SettingsInfo;
import io.github.linwancen.plugin.show.java.JavaLangDoc;
import io.github.linwancen.plugin.show.lang.base.BaseLangDoc;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * call PackageFileToPsiDoc, PsiMethodToPsiDoc, PsiClassUtils
 */
public class OwnerToPsiDocUtils {

    /**
     * 明显不可能通过 SPI 或 "xx-ClassName.ext" 关联到 Java 类的常见非类资源扩展名，
     * 避免在 TreeDoc 跨语言回退时对大量普通文件执行无意义的全局 PsiShortNamesCache 查询。
     */
    private static final Set<String> SKIP_CLASS_LOOKUP_EXTS = Set.of(
            "png", "jpg", "jpeg", "gif", "svg", "ico", "webp", "bmp",
            "class", "jar", "zip", "tar", "gz", "7z", "war", "ear",
            "md", "txt", "log", "csv", "tsv", "regexp",
            "gitignore", "gitattributes", "editorconfig", "lock",
            "sh", "bat", "cmd", "ps1", "gradle", "kts",
            "css", "scss", "less", "woff", "woff2", "ttf", "eot", "pdf"
    );

    private OwnerToPsiDocUtils() {}

    @Nullable
    private static PsiDocComment srcOrByteCodeDoc(@NotNull PsiDocCommentOwner psiDocCommentOwner) {
        PsiElement navElement;
        try {
            navElement = psiDocCommentOwner.getNavigationElement();
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
        if (navElement instanceof PsiDocCommentOwner) {
            psiDocCommentOwner = (PsiDocCommentOwner) navElement;
        }
        try {
            return psiDocCommentOwner.getDocComment();
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    public static PsiDocComment packageDoc(@Nullable PsiPackage psiPackage) {
        if (psiPackage == null) {
            return null;
        }
        @Nullable String name = psiPackage.getName();
        if (name == null || name.isEmpty()) {
            return null;
        }
        @NotNull PsiDirectory[] psiDirectories = psiPackage.getDirectories();
        for (@NotNull PsiDirectory psiDirectory : psiDirectories) {
            @Nullable PsiFile file = psiDirectory.findFile(PsiPackage.PACKAGE_INFO_FILE);
            @Nullable PsiDocComment psiDocComment = PackageFileToPsiDoc.fromPackageInfoFile(file);
            if (psiDocComment != null) {
                return psiDocComment;
            }
        }
        return null;
    }

    @Nullable
    public static <T extends SettingsInfo> String fileDoc(@NotNull T info, @Nullable PsiFile psiFile) {
        if (psiFile == null) {
            return null;
        }
        if (!(psiFile instanceof PsiClassOwner)) {
            @Nullable VirtualFile virtualFile = psiFile.getVirtualFile();
            if (shouldSkipClassLookup(virtualFile)) {
                return null;
            }
            // for SPI
            @NotNull PsiClass[] psiClasses = PsiClassUtils.nameToClass(psiFile.getName(), psiFile.getProject());
            // for "ClassName-...-ClassName.xxx"
            if (psiClasses.length == 0) {
                psiClasses = PsiClassUtils.fileToClasses(virtualFile, psiFile.getProject());
            }
            for (@NotNull PsiClass psiClass : psiClasses) {
                // support to kotlin data class
                @Nullable String doc = BaseLangDoc.resolveDoc(info, psiClass);
                if (doc != null) {
                    // Inaccurate when there are classes with the same name
                    return doc;
                }
            }
            return null;
        }
        if (PsiPackage.PACKAGE_INFO_FILE.equals(psiFile.getName())) {
            @Nullable PsiDocComment psiDocComment = PackageFileToPsiDoc.fromPackageInfoFile(psiFile);
            return JavaLangDoc.INSTANCE.docElementToStr(info, psiDocComment);
        }
        @NotNull PsiClassOwner psiClassOwner = (PsiClassOwner) psiFile;
        @NotNull PsiClass[] classes = psiClassOwner.getClasses();
        if (classes.length == 0) {
            return null;
        }
        PsiClass psiClass = classes[0];
        // support to kotlin data class
        return BaseLangDoc.resolveDoc(info, psiClass);
    }

    private static boolean shouldSkipClassLookup(@Nullable VirtualFile virtualFile) {
        if (virtualFile == null) {
            return false;
        }
        @Nullable String ext = virtualFile.getExtension();
        if (ext == null || ext.isEmpty()) {
            return false;
        }
        return SKIP_CLASS_LOOKUP_EXTS.contains(ext.toLowerCase(Locale.ROOT));
    }
}
