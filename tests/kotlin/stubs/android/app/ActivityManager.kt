package android.app

class ActivityManager {
    class MemoryInfo { var availMem: Long = Long.MAX_VALUE }
    fun getMemoryInfo(outInfo: MemoryInfo) { outInfo.availMem = Long.MAX_VALUE }
}
