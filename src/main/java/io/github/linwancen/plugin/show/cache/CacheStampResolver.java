package io.github.linwancen.plugin.show.cache;

import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 缓存数据修改戳解析器。
 * <p>
 * 职责：从各种不同视图类型的缓存键（VirtualFile、PsiElement、AbstractTreeNode、StructureViewTreeElement 等）
 * 快速、无阻塞地提取出底层数据修改戳（dataModStamp）以及对应的 VirtualFile。
 * <p>
 * 契约：
 * 1. 严格在 EDT 内存中读取快速属性（如 getModificationStamp()、getCachedDocument()），严禁阻塞。
 * 2. 对已打开的文档，优先融合 Document 内存修改戳与 VirtualFile 磁盘修改戳，确保即使尚未保存也能精确感知。
 * 3. 对可能触发读锁的 PSI 属性访问统一做异常防护，避免在新版 IDEA paint 周期触发 LockingForbiddenException。
 */
public final class CacheStampResolver {

    private CacheStampResolver() {}

    /**
     * 提取当前 key 对应的数据修改戳。
     *
     * @param project 当前项目
     * @param key     缓存键
     * @return 修改戳，若为未知对象则兜底返回工程级 PSI 修改计数
     */
    public static long getModificationStamp(@NotNull Project project, @NotNull Object key) {
        if (project.isDisposed()) {
            return -1L;
        }

        @Nullable VirtualFile file = resolveVirtualFile(key);
        if (file != null) {
            return fileStamp(file);
        }

        if (key instanceof PsiElement) {
            try {
                @NotNull PsiElement element = (PsiElement) key;
                if (element.isValid()) {
                    @Nullable PsiFile psiFile = element.getContainingFile();
                    if (psiFile != null) {
                        @Nullable VirtualFile psiVirtualFile = psiFile.getVirtualFile();
                        if (psiVirtualFile != null) {
                            return fileStamp(psiVirtualFile);
                        }
                        return psiFile.getModificationStamp();
                    }
                }
            } catch (Throwable ignored) {
                // EDT paint 周期禁止加读锁时安全降级
            }
        }

        // 兜底：使用全局 PSI 修改计数 (兼容 IDEA 2020.1+ 所有版本)
        try {
            return PsiManager.getInstance(project).getModificationTracker().getModificationCount();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 从 key 中尝试安全提取出关联的 VirtualFile（支持 AbstractTreeNode / StructureViewTreeElement 逐层展开）。
     */
    @Nullable
    public static VirtualFile resolveVirtualFile(@NotNull Object key) {
        return resolveVirtualFile(key, 0);
    }

    @Nullable
    private static VirtualFile resolveVirtualFile(@NotNull Object key, int depth) {
        if (depth > 4) {
            return null;
        }
        if (key instanceof VirtualFile) {
            return (VirtualFile) key;
        }
        if (key instanceof AbstractTreeNode) {
            try {
                @Nullable Object value = ((AbstractTreeNode<?>) key).getValue();
                if (value != null && value != key) {
                    return resolveVirtualFile(value, depth + 1);
                }
            } catch (Throwable ignored) {
                return null;
            }
            return null;
        }
        if (key instanceof StructureViewTreeElement) {
            try {
                @Nullable Object value = ((StructureViewTreeElement) key).getValue();
                if (value != null && value != key) {
                    return resolveVirtualFile(value, depth + 1);
                }
            } catch (Throwable ignored) {
                return null;
            }
            return null;
        }
        if (key instanceof PsiElement) {
            try {
                @NotNull PsiElement element = (PsiElement) key;
                if (element.isValid()) {
                    @Nullable PsiFile psiFile = element.getContainingFile();
                    if (psiFile != null) {
                        return psiFile.getVirtualFile();
                    }
                }
            } catch (Throwable ignored) {
                // EDT paint 周期禁止加读锁时安全降级
            }
            return null;
        }
        return null;
    }

    /**
     * 提取文件的最新修改戳（融合内存中已加载 Document 的修改戳与磁盘文件的修改戳）。
     */
    public static long fileStamp(@NotNull VirtualFile file) {
        long stamp = file.getModificationStamp();
        // getCachedDocument 只在内存已存在 Document 时快速返回，不会从磁盘加载或阻塞
        @Nullable Document document = FileDocumentManager.getInstance().getCachedDocument(file);
        if (document != null) {
            stamp = Math.max(stamp, document.getModificationStamp());
        }
        return stamp;
    }
}
