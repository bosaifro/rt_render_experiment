package dev.rt_render_experiment.vulkan;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDebugUtilsObjectNameInfoEXT;
import org.lwjgl.vulkan.VkDebugUtilsLabelEXT;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;


public final class VulkanObjects {
   private VulkanObjects() {}

   public static void requireSuccess(final int result, final String operation) {
      if (result != VK12.VK_SUCCESS) throw new Failure(result,operation);
   }

   public static final class Failure extends IllegalStateException {
      @java.io.Serial private static final long serialVersionUID=1L;
      private final int result;
      public Failure(int result,String operation) { super(operation+" (VkResult "+result+")");this.result=result; }
      public int result() { return result; }
      public boolean allocationFailure() { return result==VK12.VK_ERROR_OUT_OF_HOST_MEMORY || result==VK12.VK_ERROR_OUT_OF_DEVICE_MEMORY; }
   }

   public static void name(final VkDevice device, final int type, final long handle, final String label) {
      if (!device.getPhysicalDevice().getInstance().getCapabilities().VK_EXT_debug_utils) return;
      try (MemoryStack stack = MemoryStack.stackPush()) {
         EXTDebugUtils.vkSetDebugUtilsObjectNameEXT(device, VkDebugUtilsObjectNameInfoEXT.calloc(stack)
            .sType$Default().objectType(type).objectHandle(handle).pObjectName(stack.UTF8(label)));
      }
   }

   public static void marker(final VkCommandBuffer command, final String label) {
      if (!command.getDevice().getPhysicalDevice().getInstance().getCapabilities().VK_EXT_debug_utils) return;
      try (MemoryStack stack = MemoryStack.stackPush()) {
         EXTDebugUtils.vkCmdInsertDebugUtilsLabelEXT(command, VkDebugUtilsLabelEXT.calloc(stack)
            .sType$Default().pLabelName(stack.UTF8(label)));
      }
   }

   public static long shaderModule(final VkDevice device, final ByteBuffer bytes, final String label) {
      ByteBuffer code = MemoryUtil.memAlloc(bytes.remaining());
      try (MemoryStack stack = MemoryStack.stackPush()) {
         code.put(bytes.duplicate()).flip();
         var handle = stack.callocLong(1);
         requireSuccess(VK12.vkCreateShaderModule(device,
            VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, handle),
            "Create shader module " + label);
         long module = handle.get(0);
         try {
            name(device, VK12.VK_OBJECT_TYPE_SHADER_MODULE, module, label);
            return module;
         } catch (RuntimeException | Error failure) {
            VK12.vkDestroyShaderModule(device, module, null);
            throw failure;
         }
      } finally {
         MemoryUtil.memFree(code);
      }
   }
}
