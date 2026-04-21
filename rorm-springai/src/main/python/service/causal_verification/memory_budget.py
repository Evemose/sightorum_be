import logging
import os

logger = logging.getLogger(__name__)

RANDOM_STATE = 42
CI_ALPHA = 0.05

LGBM_DEFAULTS = dict(n_estimators=300, max_depth=6, learning_rate=0.05, verbose=-1)
BOOTSTRAP_SAMPLES = 20
REFUTATION_SIMULATIONS = 10
GRF_ESTIMATORS = 200
GRF_MIN_LEAF = 50

_PARAM_SPECS = {
    "lgbm_n_estimators": (300, 100, 100),
    "bootstrap_samples": (20, 5, 100),
    "refutation_simulations": (10, 3, 100),
    "grf_estimators": (200, 20, 50),
    "grf_min_leaf": (50, 50, 200),
}


def _adaptive_param(name: str, data_mb: float) -> int:
    max_val, min_val, threshold = _PARAM_SPECS[name]
    if data_mb <= threshold:
        return max_val
    return int(min_val + (max_val - min_val) * threshold / data_mb)


def _data_mb(data) -> float:
    n_rows = len(data)
    n_cols = data.encoded.shape[1] if hasattr(data, 'encoded') else data.shape[1]
    return n_rows * n_cols * 8 / (1024 * 1024)


def _rss_mb() -> float:
    try:
        with open("/proc/self/statm") as f:
            resident_pages = int(f.read().split()[1])
            return resident_pages * os.sysconf("SC_PAGE_SIZE") / (1024 * 1024)
    except (FileNotFoundError, ValueError, OSError):
        try:
            import psutil
            return psutil.Process().memory_info().rss / (1024 * 1024)
        except Exception:
            return 0.0


def _container_limit_mb() -> float:
    ecs_meta_uri = os.environ.get("ECS_CONTAINER_METADATA_URI_V4") \
                   or os.environ.get("ECS_CONTAINER_METADATA_URI")
    if ecs_meta_uri:
        try:
            import urllib.request, json
            with urllib.request.urlopen(f"{ecs_meta_uri}/task", timeout=2) as resp:
                meta = json.loads(resp.read())
                limit_str = meta.get("Limits", {}).get("Memory")
                if limit_str:
                    return int(limit_str)
        except Exception:
            pass
    for path in ("/sys/fs/cgroup/memory.max",
                 "/sys/fs/cgroup/memory.limit_in_bytes"):
        try:
            with open(path) as f:
                val = f.read().strip()
                if val not in ("max", "9223372036854771712"):
                    limit = int(val) / (1024 * 1024)
                    if limit < 500_000:
                        return limit
        except (FileNotFoundError, ValueError, PermissionError):
            pass
    try:
        with open("/sys/fs/cgroup/memory/memory.limit_in_bytes") as f:
            val = int(f.read().strip())
            if val < 500_000 * 1024 * 1024:
                return val / (1024 * 1024)
    except (FileNotFoundError, ValueError, PermissionError):
        pass
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                if line.startswith("MemTotal:"):
                    return int(line.split()[1]) / 1024
    except (FileNotFoundError, ValueError):
        pass
    return 32_000.0


def reclaim():
    import gc
    import ctypes
    gc.collect()
    try:
        ctypes.CDLL("libc.so.6").malloc_trim(0)
    except (OSError, AttributeError):
        pass


class MemoryBudget:

    def __init__(self, data):
        self.data_mb = _data_mb(data)
        self.container_mb = _container_limit_mb()
        self.pressure_ceiling = self.container_mb * 0.75
        logger.info(
            "MemoryBudget: data=%.0f MB, container=%.0f MB, "
            "pressure_ceiling=%.0f MB",
            self.data_mb, self.container_mb, self.pressure_ceiling,
        )

    def param(self, name: str) -> int:
        base = _adaptive_param(name, self.data_mb)
        min_val = _PARAM_SPECS[name][1]
        rss = _rss_mb()
        usage_frac = rss / self.container_mb if self.container_mb > 0 else 0

        if usage_frac <= 0.50:
            return base

        scale = max(0.0, 1.0 - (usage_frac - 0.50) / 0.50)
        reduced = int(min_val + (base - min_val) * scale)
        reduced = max(min_val, reduced)

        if reduced < base:
            logger.warning(
                "Memory pressure: RSS=%.0f MB (%.0f%% of %.0f MB), "
                "reducing %s from %d to %d",
                rss, usage_frac * 100, self.container_mb, name, base, reduced,
            )
        return reduced

    def lgbm_defaults(self) -> dict:
        return dict(
            n_estimators=self.param("lgbm_n_estimators"),
            max_depth=6, learning_rate=0.05, verbose=-1,
        )

    def check_and_reclaim(self):
        rss = _rss_mb()
        if rss > self.container_mb * 0.60:
            logger.info("Pre-submit reclaim: RSS=%.0f MB (%.0f%% of %.0f MB)",
                        rss, rss / self.container_mb * 100, self.container_mb)
            reclaim()
