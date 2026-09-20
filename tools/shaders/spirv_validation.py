import ctypes
import ctypes.util
from functools import lru_cache
from pathlib import Path
import shutil
import subprocess

class ContractError(RuntimeError):
    pass

@lru_cache(maxsize=None)
def validator_identity(command: str | None) -> str:
    executable = command or shutil.which("spirv-val")
    if executable:
        run = subprocess.run([executable, "--version"], text=True, capture_output=True)
        identity = (run.stdout + run.stderr).strip()
        if run.returncode or not identity:
            raise ContractError(f"cannot identify spirv-val {executable}: {identity}")
        return identity
    library_name = ctypes.util.find_library("SPIRV-Tools")
    if not library_name:
        raise ContractError("spirv-val is absent and libSPIRV-Tools could not be found")
    library = ctypes.CDLL(library_name)
    library.spvSoftwareVersionDetailsString.argtypes = []
    library.spvSoftwareVersionDetailsString.restype = ctypes.c_char_p
    details = library.spvSoftwareVersionDetailsString()
    if not details:
        raise ContractError(f"cannot identify {library_name}")
    return f"{library_name}: {details.decode('utf-8')}"


def spirv_validate(path: Path, command: str | None) -> str:
    executable = command or shutil.which("spirv-val")
    if executable:
        run = subprocess.run([executable, "--target-env", "vulkan1.2", str(path)], text=True, capture_output=True)
        if run.returncode:
            raise ContractError(f"spirv-val failed for {path.name}:\n{run.stdout}{run.stderr}")
        return validator_identity(command)
    library_name = ctypes.util.find_library("SPIRV-Tools")
    if not library_name:
        raise ContractError("spirv-val is absent and libSPIRV-Tools could not be found")
    data = path.read_bytes()
    words = (ctypes.c_uint32 * (len(data) // 4)).from_buffer_copy(data)
    library = ctypes.CDLL(library_name)
    library.spvParseTargetEnv.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_int)]
    library.spvParseTargetEnv.restype = ctypes.c_bool
    library.spvContextCreate.argtypes = [ctypes.c_int]
    library.spvContextCreate.restype = ctypes.c_void_p
    library.spvContextDestroy.argtypes = [ctypes.c_void_p]
    library.spvValidateBinary.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint32), ctypes.c_size_t, ctypes.POINTER(ctypes.c_void_p)]
    library.spvValidateBinary.restype = ctypes.c_int
    library.spvDiagnosticPrint.argtypes = [ctypes.c_void_p]
    library.spvDiagnosticDestroy.argtypes = [ctypes.c_void_p]
    environment = ctypes.c_int()
    if not library.spvParseTargetEnv(b"vulkan1.2", ctypes.byref(environment)):
        raise ContractError("libSPIRV-Tools does not recognize Vulkan 1.2")
    context, diagnostic = library.spvContextCreate(environment.value), ctypes.c_void_p()
    try:
        status = library.spvValidateBinary(context, words, len(words), ctypes.byref(diagnostic))
        if status:
            if diagnostic:
                library.spvDiagnosticPrint(diagnostic)
            raise ContractError(f"spirv-val (libSPIRV-Tools) failed for {path.name} with status {status}")
    finally:
        if diagnostic:
            library.spvDiagnosticDestroy(diagnostic)
        library.spvContextDestroy(context)
    return validator_identity(command)
