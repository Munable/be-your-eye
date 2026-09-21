# Localized MobileNet reference package `0.1.0-internal.15` review

Decision: **approved for signed internal evaluation only**.

## Package identity

Current candidate:
`similarity_mediapipe_mobilenet_v3_large_v1@0.1.0-internal.15`.
The release builder produces and binds the signed Manifest URL and SHA-256 for each candidate. This
review does not predeclare either identity and must not be used in place of the signed Catalog.

## Exact runtime graph

- class-agnostic Mobile Object Localizer, SHA-256
  `40593533fe47933022ec0dca71b8fa59f7df564d40d496faae44e4c1941e452e`;
- `prominent_object_candidates_v2`, SHA-256
  `632fb7f5a7bb7d9fb0ce5bbe6de3b702713fe7ce429e19a7d4b1c9fd65315fd1`;
- MobileNetV3 Large embedder, SHA-256
  `11af3c560dfeed7737cb4c03c23bf52a8403020784192d4dea0b74862a12828d`;
- `l2_prototype_cosine_candidates_v2`, SHA-256
  `b999d1d9bfb302494fecd6d3f856d621dbb298bd66839d7a82483a508a28ee1b`;
- preprocess `class_agnostic_localize_letterbox_multi_crop_rgb_v3`, postprocess
  `similarity_logit_state_v1`, and the signed tensor bindings.

The package evaluates up to three localized candidates plus the full-frame fallback against twelve
reference prototypes. It uses the signed publisher-aligned defaults: `match_threshold=0.4`,
`rejection_margin=0.05`, default/minimum sampling interval `100 ms`, maximum `2000 ms`, adaptive
sampling allowed.

## Release boundary

This review records package identity, exact bytes, runtime contract and license review. It makes no
accuracy, latency, power, thermal, long-run or public-release claim. Internal preparation requires
one focused load/inference sanity check. Commercial publication remains blocked until the publisher
provides an explicit signed statement covering the pretrained ImageNet provenance and commercial
redistribution of these exact weights. Runtime observations do not substitute for that signoff and
are not copied into the Catalog or Manifest as accuracy evidence.
