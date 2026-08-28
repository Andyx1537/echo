/**
 * 体验 Bot 定性评审层（EXP-BOTS §7 LLM 定性 Bot）。
 *
 * <p>让每个画像 bot 以自己的视角"读"实际回声文案，产出主观评价，补充定量层看不到的问题。
 * {@code HeuristicBotReviewer} 无需 LLM、可跑 CI；{@code LlmBotReviewer} 走 {@code ILlmClient}。</p>
 */
package com.echo.harness.review;
