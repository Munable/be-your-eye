package app.beyoureyes.monitor.feature.assistant

internal fun functionalAssistantCatalogSnapshot(): AssistantCatalogSnapshot {
    val commonTargets = listOf(
        AssistantTargetDescriptor("apple", "苹果", "apple", emptyList()),
        AssistantTargetDescriptor("cat", "猫", "cat", listOf("小猫")),
    )
    return AssistantCatalogSnapshot(
        binding = AssistantCatalogBinding(
            catalogId = "functional-assistant",
            catalogVersion = "1.0.0",
            catalogSignedPayloadSha256 = "0".repeat(64),
        ),
        modelProfiles = listOf(
            AssistantModelProfile(
                modelProfileKey = "reference_object_matching",
                packageId = "similarity_mediapipe_mobilenet_v3_large_v1",
                kind = AssistantProposalKind.REFERENCE_IMAGES,
                intentPatterns = listOf("visual.reference.*"),
                applicableScenarios = listOf("固定机位下匹配用户提供的参考目标"),
                inapplicableScenarios = listOf("人物身份识别", "没有参考图片"),
                inputRequirements = listOf("3 至 20 张参考图片", "固定纵向后摄", "持续供电"),
                targets = emptyList(),
                displayName = "MediaPipe MobileNetV3 Large Image Embedder",
            ),
            AssistantModelProfile(
                modelProfileKey = "common_objects_tensorflow_efficientdet_lite2",
                packageId = "efficientdet_lite2_object_v1",
                kind = AssistantProposalKind.VISUAL_DESCRIPTION,
                intentPatterns = listOf("object.common.*"),
                applicableScenarios = listOf("固定机位下检测签名类别表中的常见物体"),
                inapplicableScenarios = listOf("签名类别表外目标", "专业安全现象", "人物身份识别"),
                inputRequirements = listOf("目标清晰可见", "固定纵向后摄", "持续供电"),
                targets = commonTargets,
                displayName = "EfficientDet-Lite2 COCO",
            ),
            AssistantModelProfile(
                modelProfileKey = "numeric_display_reading",
                packageId = "numeric_reader_ppocrv6_medium_v1",
                kind = AssistantProposalKind.STRUCTURED_READING,
                intentPatterns = listOf("reading.numeric.*"),
                applicableScenarios = listOf("固定机位下读取清晰单行数字显示"),
                inapplicableScenarios = listOf("通用文字识别", "多行文档", "模拟指针表"),
                inputRequirements = listOf("清晰单行数字", "固定纵向后摄", "持续供电"),
                targets = emptyList(),
                displayName = "PP-OCRv6",
            ),
        ),
        currentExactObjectTargetIds = commonTargets.mapTo(hashSetOf(), AssistantTargetDescriptor::targetId),
    )
}
