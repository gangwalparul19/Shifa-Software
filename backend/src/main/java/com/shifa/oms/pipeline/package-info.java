/**
 * Configurable sales pipelines &amp; stages (CRM expansion Phase 3, Req 16, 17).
 *
 * <p>An <em>additive configuration overlay</em> on top of the existing
 * {@link com.shifa.oms.lead.LeadStatus} engine: the seeded Default pipeline
 * mirrors the enum funnel 1:1 and stays governed by the frozen enum transition
 * table, while custom pipelines are governed by their stage configuration
 * ({@link com.shifa.oms.pipeline.PipelineService#canTransition}).
 */
package com.shifa.oms.pipeline;
