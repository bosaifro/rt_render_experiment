package dev.rt_render_experiment.vulkan;

import org.lwjgl.vulkan.VkDevice;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;
import org.slf4j.Logger;


public final class VulkanPipelineCache {
   static final int HEADER_BYTES = 32;
   static final int HEADER_VERSION_ONE = 1;
   static final long MAX_CACHE_BYTES = 64L * 1024L * 1024L;
   static final int MAX_CACHE_FILES = 4;
   private static final int MAX_QUERY_ATTEMPTS = 3;
   private static final Logger LOGGER = LoggerFactory.getLogger(VulkanPipelineCache.class);

   public record Persistence(int bytes, boolean written, String reason) {
      public Persistence {
         if (bytes < 0) {
            throw new IllegalArgumentException("Pipeline-cache byte count must be non-negative");
         }
         Objects.requireNonNull(reason, "reason");
      }
   }

   private final VkDevice device;
   private final Path path;
   private final boolean persistent;
   private final int inputBytes;
   private final String inputSha256;
   private long handle;

   public static VulkanPipelineCache create(
      final VkDevice device,
      final String packageRevision,
      final boolean persistent
   ) {
      Objects.requireNonNull(device, "device");
      Path path = cachePath(Path.of(""), packageRevision);
      byte[] initial = persistent ? readCandidate(path) : new byte[0];
      long handle = createHandle(device, initial);
      int acceptedInputBytes = handle != VK12.VK_NULL_HANDLE ? initial.length : 0;
      if (handle == VK12.VK_NULL_HANDLE && initial.length > 0) {
         LOGGER.warn(
            "RT_RENDER_EXPERIMENT_PIPELINE_CACHE_RETRY reason=seeded-create-failed inputBytes={} path={}",
            initial.length,
            path
         );
         handle = createHandle(device, new byte[0]);
      }
      if (handle != VK12.VK_NULL_HANDLE) {
         VulkanObjects.name(device, VK12.VK_OBJECT_TYPE_PIPELINE_CACHE, handle, "RtRenderExperiment pipeline cache");
      }
      LOGGER.info(
         "RT_RENDER_EXPERIMENT_PIPELINE_CACHE_OPEN schema=1 mode={} inputBytes={} enabled={} path={}",
         persistent ? "persistent" : "memory",
         acceptedInputBytes,
         handle != VK12.VK_NULL_HANDLE,
         persistent ? path : "none"
      );
      return new VulkanPipelineCache(
         device,
         path,
         persistent,
         acceptedInputBytes,
         acceptedInputBytes == 0 ? "" : sha256(initial),
         handle
      );
   }

   private VulkanPipelineCache(
      final VkDevice device,
      final Path path,
      final boolean persistent,
      final int inputBytes,
      final String inputSha256,
      final long handle
   ) {
      this.device = device;
      this.path = path;
      this.persistent = persistent;
      this.inputBytes = inputBytes;
      this.inputSha256 = inputSha256;
      this.handle = handle;
   }

   public long handle() {
      return this.handle;
   }

   public int inputBytes() {
      return this.inputBytes;
   }

   public Persistence close() {
      long ownedHandle = this.handle;
      this.handle = VK12.VK_NULL_HANDLE;
      if (ownedHandle == VK12.VK_NULL_HANDLE) {
         return new Persistence(0, false, "disabled");
      }

      Persistence result;
      try {
         result = this.persistent
            ? persist(this.device, ownedHandle, this.path, this.inputSha256)
            : new Persistence(0, false, "memory-only");
      } catch (RuntimeException failure) {
         LOGGER.warn("RT_RENDER_EXPERIMENT_PIPELINE_CACHE_WRITE_FAILED reason=runtime path={}", this.path, failure);
         result = new Persistence(0, false, "runtime-failure");
      } finally {
         VK12.vkDestroyPipelineCache(this.device, ownedHandle, null);
      }
      if (result.reason().equals("written") || result.reason().equals("unchanged")) {
         pruneOwnedCaches(this.path);
      }
      LOGGER.info(
         "RT_RENDER_EXPERIMENT_PIPELINE_CACHE_CLOSE schema=1 inputBytes={} outputBytes={} written={} reason={} path={}",
         this.inputBytes,
         result.bytes(),
         result.written(),
         result.reason(),
         this.persistent ? this.path : "none"
      );
      return result;
   }


   public void discard() {
      long ownedHandle = this.handle;
      this.handle = VK12.VK_NULL_HANDLE;
      if (ownedHandle != VK12.VK_NULL_HANDLE) {
         VK12.vkDestroyPipelineCache(this.device, ownedHandle, null);
      }
   }

   static Path cachePath(final Path root, final String packageRevision) {
      Objects.requireNonNull(root, "root");
      if (packageRevision == null || !packageRevision.matches("[a-f0-9]{64}")) {
         throw new IllegalArgumentException("Pipeline cache package revision must be a lowercase SHA-256 digest");
      }
      return root.toAbsolutePath()
         .normalize()
         .resolve("rt_render_experiment-cache")
         .resolve("pipelines")
         .resolve("v1")
         .resolve(packageRevision + ".bin");
   }

   static boolean validVersionOneHeader(final byte[] data) {
      if (data == null || data.length < HEADER_BYTES || data.length > MAX_CACHE_BYTES) {
         return false;
      }
      ByteBuffer header = ByteBuffer.wrap(data, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
      int headerSize = header.getInt();
      int version = header.getInt();
      return headerSize == HEADER_BYTES && version == HEADER_VERSION_ONE && headerSize <= data.length;
   }

   private static byte[] readCandidate(final Path path) {
      try {
         if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return new byte[0];
         }
         if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            LOGGER.warn("RT_RENDER_EXPERIMENT_PIPELINE_CACHE_IGNORED reason=not-regular path={}", path);
            return new byte[0];
         }
         long size = Files.size(path);
         if (size < HEADER_BYTES || size > MAX_CACHE_BYTES) {
            LOGGER.warn("RT_RENDER_EXPERIMENT_PIPELINE_CACHE_IGNORED reason=size bytes={} path={}", size, path);
            return new byte[0];
         }
         byte[] data = Files.readAllBytes(path);
         if (!validVersionOneHeader(data)) {
            LOGGER.warn("RT_RENDER_EXPERIMENT_PIPELINE_CACHE_IGNORED reason=header bytes={} path={}", data.length, path);
            return new byte[0];
         }
         return data;
      } catch (IOException exception) {
         LOGGER.warn("RT_RENDER_EXPERIMENT_PIPELINE_CACHE_IGNORED reason=read-failure path={}", path, exception);
         return new byte[0];
      }
   }

   private static long createHandle(final VkDevice device, final byte[] initial) {
      ByteBuffer nativeInitial = null;
      try (MemoryStack stack = MemoryStack.stackPush()) {
         VkPipelineCacheCreateInfo createInfo = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
         if (initial.length > 0) {
            nativeInitial = MemoryUtil.memAlloc(initial.length);
            nativeInitial.put(initial).flip();
            createInfo.pInitialData(nativeInitial);
         }
         long[] output = new long[1];
         int status = VK12.vkCreatePipelineCache(device, createInfo, null, output);
         if (status != VK12.VK_SUCCESS) {
            LOGGER.warn(
               "RT_RENDER_EXPERIMENT_PIPELINE_CACHE_CREATE_FAILED status={} inputBytes={}",
               Integer.toString(status),
               initial.length
            );
            return VK12.VK_NULL_HANDLE;
         }
         return output[0];
      } finally {
         if (nativeInitial != null) {
            MemoryUtil.memFree(nativeInitial);
         }
      }
   }

   private static Persistence persist(
      final VkDevice device,
      final long handle,
      final Path path,
      final String inputSha256
   ) {
      ByteBuffer data = null;
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer size = stack.mallocPointer(1);
         int status = VK12.vkGetPipelineCacheData(device, handle, size, null);
         if (status != VK12.VK_SUCCESS) {
            return new Persistence(0, false, "size-query-" + Integer.toString(status));
         }

         long required = size.get(0);
         for (int attempt = 0; attempt < MAX_QUERY_ATTEMPTS; attempt++) {
            if (required < HEADER_BYTES || required > MAX_CACHE_BYTES || required > Integer.MAX_VALUE) {
               return new Persistence(0, false, "size-" + required);
            }
            if (data != null) {
               MemoryUtil.memFree(data);
            }
            data = MemoryUtil.memAlloc((int)required);
            size.put(0, required);
            status = VK12.vkGetPipelineCacheData(device, handle, size, data);
            long actual = size.get(0);
            if (status == VK12.VK_INCOMPLETE || actual > data.capacity()) {
               required = actual > data.capacity() ? actual : Math.min(MAX_CACHE_BYTES + 1L, data.capacity() * 2L);
               continue;
            }
            if (status != VK12.VK_SUCCESS) {
               return new Persistence(0, false, "data-query-" + Integer.toString(status));
            }
            if (actual < HEADER_BYTES || actual > data.capacity()) {
               return new Persistence(0, false, "returned-size-" + actual);
            }
            byte[] bytes = new byte[(int)actual];
            data.position(0).limit((int)actual).get(bytes);
            if (!validVersionOneHeader(bytes)) {
               return new Persistence(bytes.length, false, "returned-header");
            }
            if (!inputSha256.isEmpty() && sha256(bytes).equals(inputSha256)) {
               return new Persistence(bytes.length, false, "unchanged");
            }
            writeAtomically(path, bytes);
            return new Persistence(bytes.length, true, "written");
         }
         return new Persistence(0, false, "query-unstable");
      } catch (IOException exception) {
         LOGGER.warn("RT_RENDER_EXPERIMENT_PIPELINE_CACHE_WRITE_FAILED path={}", path, exception);
         return new Persistence(0, false, "write-failure");
      } finally {
         if (data != null) {
            MemoryUtil.memFree(data);
         }
      }
   }

   static void writeAtomically(final Path path, final byte[] data) throws IOException {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(data, "data");
      if (!validVersionOneHeader(data)) {
         throw new IOException("Refusing to persist malformed pipeline-cache data");
      }
      Path parent = Objects.requireNonNull(path.getParent(), "pipeline cache parent");
      Files.createDirectories(parent);
      Path scratch = Files.createTempFile(parent, ".rt_render_experiment-pipeline-", ".tmp");
      boolean published = false;
      try {
         try (FileChannel output = FileChannel.open(scratch, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer bytes = ByteBuffer.wrap(data);
            while (bytes.hasRemaining()) {
               output.write(bytes);
            }
            output.force(true);
         }
         try {
            Files.move(scratch, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
         } catch (AtomicMoveNotSupportedException unsupported) {
            LOGGER.warn("RT_RENDER_EXPERIMENT_PIPELINE_CACHE_NONATOMIC path={} reason={}", path, unsupported.getMessage());
            Files.move(scratch, path, StandardCopyOption.REPLACE_EXISTING);
         }
         published = true;
      } finally {
         if (!published) {
            Files.deleteIfExists(scratch);
         }
      }
   }

   static void pruneOwnedCaches(final Path current) {
      Path parent = current.getParent();
      if (parent == null || !Files.isDirectory(parent)) {
         return;
      }
      try (Stream<Path> children = Files.list(parent)) {
         List<Path> older = children
            .filter(path -> !path.equals(current))
            .filter(path -> path.getFileName().toString().matches("[a-f0-9]{64}\\.bin"))
            .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            .sorted(Comparator.comparingLong(VulkanPipelineCache::lastModifiedOrMinimum).reversed())
            .toList();
         for (int index = MAX_CACHE_FILES - 1; index < older.size(); index++) {
            Path retired = older.get(index);
            try {
               Files.delete(retired);
               LOGGER.info("RT_RENDER_EXPERIMENT_PIPELINE_CACHE_PRUNED path={}", retired);
            } catch (IOException failure) {
               LOGGER.warn("RT_RENDER_EXPERIMENT_PIPELINE_CACHE_PRUNE_FAILED path={}", retired, failure);
            }
         }
      } catch (IOException failure) {
         LOGGER.warn("RT_RENDER_EXPERIMENT_PIPELINE_CACHE_PRUNE_FAILED directory={}", parent, failure);
      }
   }

   private static long lastModifiedOrMinimum(final Path path) {
      try {
         return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
      } catch (IOException failure) {
         return Long.MIN_VALUE;
      }
   }

   private static String sha256(final byte[] data) {
      try {
         return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
      } catch (NoSuchAlgorithmException impossible) {
         throw new AssertionError("Java 25 must provide SHA-256", impossible);
      }
   }
}
