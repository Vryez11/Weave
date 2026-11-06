rootProject.name = "weave"

include(
    "feed-service",
    "post-service",
    "user-service",
    "like-service",
    "recommendation-service",
    "ad-service",
    "libs:observability",
    "libs:fault-injection",
    "e2e",
)
