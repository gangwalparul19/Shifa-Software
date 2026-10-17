package com.shifa.oms.pipeline.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Reorder a pipeline's stages by the supplied ordered list of stage ids. */
public record StageReorderRequest(@NotNull List<Long> stageIds) {
}
