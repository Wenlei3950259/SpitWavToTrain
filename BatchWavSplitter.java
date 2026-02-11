import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * WAV音频批量切割+长静音过滤工具 (适用于截取后用于训练)
 * 核心流程：1.切割为10秒/段 → 2.删除时长不足10秒的片段 → 3.删除包含≥5秒连续静音的片段
 * 输出规则：所有分段直接输出到总目录，不创建子文件夹，仅保留完整10秒的高质量音频用于训练
 * 支持：8/16位PCM WAV、单/双声道、批量处理、Ubuntu/Windows跨平台
 */
public class BatchWavSplitter {
    // ---------------------- 可配置常量（你设置的20秒切割、8秒静音阈值） ----------------------
    private static final int SPLIT_SECONDS = 10;        // 切割时长（秒）
    private static final double SILENCE_DB_THRESHOLD = -30.0; // 静音帧阈值
    private static final int CONTINUOUS_SILENCE_SECONDS = 5; // 需裁剪的连续静音时长
    private static final double MIN_AMPLITUDE = 1e-6;   // 避免log10(0)
    private static final int BUFFER_SIZE = 8192;        // 缓冲区大小
    private static final DecimalFormat FORMAT = new DecimalFormat("000");

    // ---------------------- 对外批量处理入口 ----------------------
    public static void batchSplitWav(String inputDirPath, String outputRootDirPath) throws IOException {
        batchSplitWav(inputDirPath, outputRootDirPath, false);
    }

    public static void batchSplitWav(String inputDirPath, String outputRootDirPath, boolean traverseSubDir) throws IOException {
        File inputDir = new File(inputDirPath);
        if (!inputDir.exists() || !inputDir.isDirectory()) {
            throw new FileNotFoundException("输入文件夹不存在：" + inputDirPath);
        }
        File outputRootDir = new File(outputRootDirPath);
        if (!outputRootDir.exists()) outputRootDir.mkdirs(); // 仅创建总输出目录（spit）

        File[] wavFiles = traverseWavFiles(inputDir, traverseSubDir);
        if (wavFiles == null || wavFiles.length == 0) {
            System.out.println("未找到WAV文件，处理结束");
            return;
        }

        // 日志适配：去掉子文件夹描述
        System.out.println("========== 批量WAV切割+长静音过滤开始 ==========");
        System.out.println("配置：" + SPLIT_SECONDS + "秒/段 | ≤" + SILENCE_DB_THRESHOLD + "dB为静音 | 包含≥" + CONTINUOUS_SILENCE_SECONDS + "秒静音的片段将被删除");
        System.out.println("输入：" + inputDir + " | 统一输出：" + outputRootDir + " | 待处理：" + wavFiles.length + "个WAV");
        System.out.println("===============================================");

        int totalSuccess = 0, totalFail = 0;
        for (int i = 0; i < wavFiles.length; i++) {
            File wavFile = wavFiles[i];
            System.out.println("\n【" + (i + 1) + "/" + wavFiles.length + "】处理：" + wavFile.getName());
            try {
                // 核心修改1：删除创建子文件夹，直接传入总输出目录（spit）
                processSingleWav(wavFile, outputRootDir);
                System.out.println("【成功】处理完成，分段已输出到总目录");
                totalSuccess++;
            } catch (Exception e) {
                System.err.println("【失败】出错：" + e.getMessage());
                totalFail++;
            }
            System.out.println("-----------------------------------------------");
        }

        System.out.println("========== 处理结束 ==========");
        System.out.println("总处理：" + wavFiles.length + " | 成功：" + totalSuccess + " | 失败：" + totalFail);
        System.out.println("所有音频分段已统一输出至：" + outputRootDir.getAbsolutePath());
    }

    // ---------------------- 单WAV处理：切割→检测→裁剪（无修改，仅适配入参） ----------------------
    private static void processSingleWav(File inputWavFile, File outputDir) throws IOException {
        WavHeader header = parseWavHeader(inputWavFile);
        long totalSegments = splitTo30sSegments(inputWavFile, outputDir, header);
        if (totalSegments > 0) {
            cropSilenceInAllSegments(outputDir, header);
        }
    }

    // ---------------------- 切割为指定时长分段（输出到总目录，文件名：原歌名_00x.wav） ----------------------
    private static long splitTo30sSegments(File inputWavFile, File outputDir, WavHeader header) throws IOException {
        long bytesPerSecond = (long) header.sampleRate * header.channels * (header.bitsPerSample / 8);
        long splitBytes = bytesPerSecond * SPLIT_SECONDS;
        long totalDataBytes = header.dataSize;
        long totalSegments = (totalDataBytes + splitBytes - 1) / splitBytes;
        // 提取原文件名（不含后缀），保证分段名唯一：比如 9_天黑黑-孙燕姿_(Vocals)_001.wav
        String fileName = inputWavFile.getName().substring(0, inputWavFile.getName().lastIndexOf("."));

        System.out.println("文件信息：" + header.sampleRate + "Hz | " + header.channels + "声道 | " + header.bitsPerSample + "位");
        System.out.println("总时长：" + String.format("%.2f", (double) totalDataBytes / bytesPerSecond) + "秒 | 预计切割为" + totalSegments + "段");

        try (RandomAccessFile raf = new RandomAccessFile(inputWavFile, "r")) {
            raf.seek(header.dataStartPos);
            long segmentNum = 1, remaining = totalDataBytes;
            while (remaining > 0) {
                long currentBytes = Math.min(splitBytes, remaining);
                // 核心修改2：直接输出到总目录，文件名天然唯一，无重复
                String outputPath = outputDir + File.separator + fileName + "_" + FORMAT.format(segmentNum) + ".wav";
                writeSegmentWav(outputPath, header, raf, currentBytes);
                segmentNum++;
                remaining -= currentBytes;
            }
        }
        return totalSegments;
    }

    // ---------------------- 核心：遍历总目录所有分段，删除包含长静音或时长不足的片段 ----------------------
    private static void cropSilenceInAllSegments(File outputDir, WavHeader header) throws IOException {
        // 核心修改3：直接遍历总目录的所有分段（含_的WAV）
        File[] segmentFiles = outputDir.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".wav") && name.contains("_")
        );
        if (segmentFiles == null || segmentFiles.length == 0) return;

        // 日志适配：显示总目录检测
        System.out.println("\n检测总目录长静音（≥" + CONTINUOUS_SILENCE_SECONDS + "秒）及时长：");
        int deletedCount = 0, reservedCount = 0;

        for (File segFile : segmentFiles) {
            System.out.print("处理：" + segFile.getName() + " → ");
            double duration = getFileDuration(segFile, header);

            // 新增：检查时长是否达到10秒（允许0.1秒误差）
            if (duration < SPLIT_SECONDS - 0.1) {
                segFile.delete();
                System.out.println("时长不足" + SPLIT_SECONDS + "秒（" + String.format("%.2f", duration) + "秒），已删除");
                deletedCount++;
                continue;
            }

            List<SilenceSegment> silenceSegments = detectLongSilenceSegments(segFile, header);

            // 原逻辑：只要检测到≥5秒的静音段，直接删除整个片段
            if (!silenceSegments.isEmpty()) {
                segFile.delete();
                System.out.println("检测到≥" + CONTINUOUS_SILENCE_SECONDS + "秒静音，已删除整个片段");
                deletedCount++;
            } else {
                System.out.println("无长静音且时长达标，保留（" + String.format("%.2f", duration) + "秒）");
                reservedCount++;
            }
        }

        System.out.println("本文件检测统计：保留" + reservedCount + "个 | 删除" + deletedCount + "个");
    }

    // ---------------------- 检测长静音段位置（无修改） ----------------------
    private static List<SilenceSegment> detectLongSilenceSegments(File wavFile, WavHeader header) throws IOException {
        List<SilenceSegment> silenceSegments = new ArrayList<>();
        if (header.bitsPerSample != 8 && header.bitsPerSample != 16) {
            throw new IllegalArgumentException("仅支持8/16位PCM");
        }

        long maxSampleValue = header.bitsPerSample == 8 ? 127 : 32767;
        int blockAlign = header.channels * (header.bitsPerSample / 8);
        long silenceThresholdFrames = (long) header.sampleRate * CONTINUOUS_SILENCE_SECONDS;

        long currentFrame = 0;
        long silenceStartFrame = -1;

        try (RandomAccessFile raf = new RandomAccessFile(wavFile, "r")) {
            raf.seek(header.dataStartPos);
            byte[] buffer = new byte[BUFFER_SIZE];
            int readLen;

            while ((readLen = raf.read(buffer)) != -1) {
                int validBytes = (readLen / blockAlign) * blockAlign;
                if (validBytes == 0) break;

                for (int i = 0; i < validBytes; i += blockAlign) {
                    double frameDb = calculateFrameDb(buffer, i, header, maxSampleValue);
                    if (frameDb <= SILENCE_DB_THRESHOLD) {
                        if (silenceStartFrame == -1) {
                            silenceStartFrame = currentFrame;
                        }
                    } else {
                        if (silenceStartFrame != -1 && (currentFrame - silenceStartFrame) >= silenceThresholdFrames) {
                            silenceSegments.add(new SilenceSegment(silenceStartFrame, currentFrame - 1));
                        }
                        silenceStartFrame = -1;
                    }
                    currentFrame++;
                }
            }

            if (silenceStartFrame != -1 && (currentFrame - silenceStartFrame) >= silenceThresholdFrames) {
                silenceSegments.add(new SilenceSegment(silenceStartFrame, currentFrame - 1));
            }
        }

        return silenceSegments;
    }

    // ---------------------- 裁剪静音段，提取有效音频数据（无修改） ----------------------
    private static byte[] cropSilenceSegments(File wavFile, WavHeader header, List<SilenceSegment> silenceSegments) throws IOException {
        int blockAlign = header.channels * (header.bitsPerSample / 8);
        long totalFrames = (wavFile.length() - header.dataStartPos) / blockAlign;
        List<ValidSegment> validSegments = new ArrayList<>();

        long lastEndFrame = -1;
        for (SilenceSegment silence : silenceSegments) {
            if (silence.startFrame > lastEndFrame + 1) {
                validSegments.add(new ValidSegment(lastEndFrame + 1, silence.startFrame - 1));
            }
            lastEndFrame = silence.endFrame;
        }
        if (lastEndFrame < totalFrames - 1) {
            validSegments.add(new ValidSegment(lastEndFrame + 1, totalFrames - 1));
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (RandomAccessFile raf = new RandomAccessFile(wavFile, "r")) {
            for (ValidSegment valid : validSegments) {
                long startByte = valid.startFrame * blockAlign + header.dataStartPos;
                long validFrames = valid.endFrame - valid.startFrame + 1;
                long validBytes = validFrames * blockAlign;

                raf.seek(startByte);
                byte[] buffer = new byte[BUFFER_SIZE];
                long remaining = validBytes;
                while (remaining > 0) {
                    int readLen = (int) Math.min(BUFFER_SIZE, remaining);
                    int actualRead = raf.read(buffer, 0, readLen);
                    if (actualRead == -1) break;
                    baos.write(buffer, 0, actualRead);
                    remaining -= actualRead;
                }
            }
        }

        return baos.toByteArray();
    }

    // ---------------------- 生成裁剪后的有效音频文件（无修改） ----------------------
    private static void writeValidAudioFile(String outputPath, WavHeader header, byte[] validData) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outputPath)) {
            fos.write("RIFF".getBytes());
            int riffSize = 36 + validData.length;
            fos.write(intToBytes(riffSize, false));
            fos.write("WAVE".getBytes());

            fos.write("fmt ".getBytes());
            fos.write(intToBytes(16, false));
            fos.write(shortToBytes((short) 1, false));
            fos.write(shortToBytes(header.channels, false));
            fos.write(intToBytes(header.sampleRate, false));
            fos.write(intToBytes(header.byteRate, false));
            fos.write(shortToBytes(header.blockAlign, false));
            fos.write(shortToBytes(header.bitsPerSample, false));

            fos.write("data".getBytes());
            fos.write(intToBytes(validData.length, false));
            fos.write(validData);
        }
    }

    // ---------------------- 辅助方法：计算单个帧的平均分贝（无修改） ----------------------
    private static double calculateFrameDb(byte[] buffer, int offset, WavHeader header, long maxSampleValue) {
        double totalAmp = 0.0;
        for (int c = 0; c < header.channels; c++) {
            long sampleValue;
            if (header.bitsPerSample == 8) {
                sampleValue = Math.abs(buffer[offset + c] - 128);
            } else {
                sampleValue = Math.abs(ByteBuffer.wrap(buffer, offset + c * 2, 2).order(ByteOrder.LITTLE_ENDIAN).getShort());
            }
            totalAmp += (double) sampleValue / maxSampleValue;
        }
        double avgAmp = Math.max(totalAmp / header.channels, MIN_AMPLITUDE);
        return 20 * Math.log10(avgAmp);
    }

    // ---------------------- 辅助方法：获取文件时长（秒）（无修改） ----------------------
    private static double getFileDuration(File wavFile, WavHeader header) {
        long dataBytes = wavFile.length() - header.dataStartPos;
        long bytesPerSecond = (long) header.sampleRate * header.channels * (header.bitsPerSample / 8);
        return (double) dataBytes / bytesPerSecond;
    }

    // ---------------------- WAV头部解析（无修改） ----------------------
    private static WavHeader parseWavHeader(File wavFile) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(wavFile, "r")) {
            WavHeader header = new WavHeader();
            byte[] buf = new byte[4];

            readFully(raf, buf);
            if (!new String(buf).equals("RIFF")) throw new IllegalArgumentException("非RIFF WAV");
            readFully(raf, buf);
            header.riffSize = bytesToInt(buf, false);
            readFully(raf, buf);
            if (!new String(buf).equals("WAVE")) throw new IllegalArgumentException("非WAVE格式");

            while (true) {
                readFully(raf, buf);
                if (new String(buf).equals("fmt ")) break;
                readFully(raf, buf);
                raf.seek(raf.getFilePointer() + bytesToInt(buf, false));
            }

            readFully(raf, buf);
            if (bytesToInt(buf, false) != 16) throw new IllegalArgumentException("非PCM编码");
            readFully(raf, buf, 2);
            if (bytesToShort(buf, false) != 1) throw new IllegalArgumentException("非PCM编码");
            readFully(raf, buf, 2);
            header.channels = bytesToShort(buf, false);
            readFully(raf, buf);
            header.sampleRate = bytesToInt(buf, false);
            readFully(raf, buf);
            header.byteRate = bytesToInt(buf, false);
            readFully(raf, buf, 2);
            header.blockAlign = bytesToShort(buf, false);
            readFully(raf, buf, 2);
            header.bitsPerSample = bytesToShort(buf, false);

            while (true) {
                readFully(raf, buf);
                if (new String(buf).equals("data")) break;
                readFully(raf, buf);
                raf.seek(raf.getFilePointer() + bytesToInt(buf, false));
            }
            readFully(raf, buf);
            header.dataSize = bytesToInt(buf, false);
            header.dataStartPos = raf.getFilePointer();

            return header;
        }
    }

    // ---------------------- 写入分段WAV文件（无修改） ----------------------
    private static void writeSegmentWav(String outputPath, WavHeader header, RandomAccessFile raf, long dataBytes) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outputPath)) {
            fos.write("RIFF".getBytes());
            fos.write(intToBytes(36 + (int) dataBytes, false));
            fos.write("WAVE".getBytes());
            fos.write("fmt ".getBytes());
            fos.write(intToBytes(16, false));
            fos.write(shortToBytes((short) 1, false));
            fos.write(shortToBytes(header.channels, false));
            fos.write(intToBytes(header.sampleRate, false));
            fos.write(intToBytes(header.byteRate, false));
            fos.write(shortToBytes(header.blockAlign, false));
            fos.write(shortToBytes(header.bitsPerSample, false));
            fos.write("data".getBytes());
            fos.write(intToBytes((int) dataBytes, false));

            byte[] buf = new byte[BUFFER_SIZE];
            long remaining = dataBytes;
            while (remaining > 0) {
                int read = (int) Math.min(buf.length, remaining);
                int actual = raf.read(buf, 0, read);
                if (actual == -1) break;
                fos.write(buf, 0, actual);
                remaining -= actual;
            }
        }
    }

    // ---------------------- 字节操作工具方法（无修改） ----------------------
    private static void readFully(RandomAccessFile raf, byte[] b) throws IOException {
        readFully(raf, b, b.length);
    }

    private static void readFully(RandomAccessFile raf, byte[] b, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = raf.read(b, read, len - read);
            if (n == -1) throw new EOFException("非标准WAV");
            read += n;
        }
    }

    private static int bytesToInt(byte[] b, boolean bigEndian) {
        ByteBuffer bb = ByteBuffer.wrap(b);
        if (!bigEndian) bb.order(ByteOrder.LITTLE_ENDIAN);
        return bb.getInt();
    }

    private static short bytesToShort(byte[] b, boolean bigEndian) {
        ByteBuffer bb = ByteBuffer.wrap(b);
        if (!bigEndian) bb.order(ByteOrder.LITTLE_ENDIAN);
        return bb.getShort();
    }

    private static byte[] intToBytes(int i, boolean bigEndian) {
        ByteBuffer bb = ByteBuffer.allocate(4);
        if (!bigEndian) bb.order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(i);
        return bb.array();
    }

    private static byte[] shortToBytes(short s, boolean bigEndian) {
        ByteBuffer bb = ByteBuffer.allocate(2);
        if (!bigEndian) bb.order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort(s);
        return bb.array();
    }

    // ---------------------- 文件夹遍历（无修改） ----------------------
    private static File[] traverseWavFiles(File dir, boolean traverseSubDir) {
        FileFilter wavFilter = f -> f.isFile() && f.getName().toLowerCase().endsWith(".wav");
        File[] wavFiles = dir.listFiles(wavFilter);

        if (traverseSubDir && wavFiles != null) {
            File[] subDirs = dir.listFiles(File::isDirectory);
            if (subDirs != null) {
                for (File subDir : subDirs) {
                    File[] subWav = traverseWavFiles(subDir, true);
                    wavFiles = mergeFileArrays(wavFiles, subWav);
                }
            }
        }
        return wavFiles;
    }

    private static File[] mergeFileArrays(File[] a1, File[] a2) {
        if (a1 == null || a1.length == 0) return a2;
        if (a2 == null || a2.length == 0) return a1;
        File[] res = new File[a1.length + a2.length];
        System.arraycopy(a1, 0, res, 0, a1.length);
        System.arraycopy(a2, 0, res, a1.length, a2.length);
        return res;
    }

    // ---------------------- 实体类：静音段（无修改） ----------------------
    private static class SilenceSegment {
        long startFrame;
        long endFrame;

        public SilenceSegment(long startFrame, long endFrame) {
            this.startFrame = startFrame;
            this.endFrame = endFrame;
        }
    }

    // ---------------------- 实体类：有效段（无修改） ----------------------
    private static class ValidSegment {
        long startFrame;
        long endFrame;

        public ValidSegment(long startFrame, long endFrame) {
            this.startFrame = startFrame;
            this.endFrame = endFrame;
        }
    }

    // ---------------------- WAV头部封装类（无修改） ----------------------
    private static class WavHeader {
        int riffSize;
        short channels;
        int sampleRate;
        int byteRate;
        short blockAlign;
        short bitsPerSample;
        long dataSize;
        long dataStartPos;
    }

    // ---------------------- 测试主方法（你的原路径，无修改） ----------------------
    public static void main(String[] args) {
        String inputDir = "C:\\Users\\kevin\\Desktop\\下载音乐\\孙燕姿\\vo";
        String outputRootDir = "C:\\Users\\kevin\\Desktop\\下载音乐\\孙燕姿\\vo\\spit";

        try {
            BatchWavSplitter.batchSplitWav(inputDir, outputRootDir);
        } catch (Exception e) {
            System.err.println("初始化失败：" + e.getMessage());
            e.printStackTrace();
        }
    }
}