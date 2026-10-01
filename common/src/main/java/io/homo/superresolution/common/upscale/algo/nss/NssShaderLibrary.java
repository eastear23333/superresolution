/*
 * Super Resolution
 * Copyright (c) 2026. 187J3X1-114514
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.homo.superresolution.common.upscale.algo.nss;

import io.homo.superresolution.core.utils.FileReadHelper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NSS shader 资源加载器（含 {@code #include} 展开）。
 *
 * <p>Arm 的 NSS shader 用 {@code #include "typedefs.h"} 这种<b>局部</b>形式互相引用，
 * 而 SR 框架的 glslang includer 根目录是 {@code /shader/}（局部 include 会被解析成
 * {@code /shader/<name>}），对 {@code /shader/nss/} 下的头文件解析不到。因此这里在交给
 * glslang 之前先把 include 文本内联展开，保持 shader 源文件与 Arm 原始版本逐字节一致
 * （不修改任何被移植的 shader）。
 *
 * <p>展开是纯文本的：头文件自带的 {@code #ifndef} 守卫原样保留，重复 include 依旧被
 * 预处理器的守卫拦掉；只有真实存在的循环会被检测并报错。
 */
public final class NssShaderLibrary {

    /** 所有 NSS shader 与头文件都平铺在这个目录下。 */
    private static final String ROOT = "/shader/nss/";

    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile("^\\s*#\\s*include\\s+\"([^\"]+)\"\\s*$");

    private static final Map<String, String> EXPANDED_CACHE = new HashMap<>();

    private NssShaderLibrary() {
    }

    /**
     * 载入并展开一个 NSS shader 源文件。
     *
     * @param fileName 相对 {@code /shader/nss/} 的文件名，例如 {@code 1_pre_process.frag}
     *
     * @return 已完成 include 展开的 GLSL 源
     */
    public static synchronized String load(String fileName) {
        String cached = EXPANDED_CACHE.get(fileName);
        if (cached != null) {
            return cached;
        }
        String expanded = expand(fileName, new ArrayDeque<>());
        EXPANDED_CACHE.put(fileName, expanded);
        return expanded;
    }

    private static String expand(String fileName, Deque<String> stack) {
        if (stack.contains(fileName)) {
            throw new IllegalStateException("NSS shader include cycle: " + stack + " -> " + fileName);
        }
        stack.push(fileName);
        try {
            List<String> lines = readLines(fileName);
            StringBuilder out = new StringBuilder();
            for (String line : lines) {
                Matcher matcher = INCLUDE_PATTERN.matcher(line);
                if (matcher.matches()) {
                    String header = matcher.group(1);
                    out.append("// >>> #include \"").append(header).append("\"\n");
                    out.append(expand(header, stack));
                    out.append("// <<< #include \"").append(header).append("\"\n");
                } else {
                    out.append(line).append('\n');
                }
            }
            return out.toString();
        } finally {
            stack.pop();
        }
    }

    private static List<String> readLines(String fileName) {
        ArrayList<String> lines = FileReadHelper.readText(ROOT + fileName);
        if (lines == null || lines.isEmpty()) {
            throw new IllegalStateException("NSS shader resource is missing: " + ROOT + fileName);
        }
        return lines;
    }
}
