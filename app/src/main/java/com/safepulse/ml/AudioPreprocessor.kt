package com.safepulse.ml

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Audio preprocessing utilities for TFLite model input
 */
object AudioPreprocessor {
    
    /**
     * Normalize audio samples to [-1.0, 1.0] range
     * This makes the detection work with both loud and normal voice volumes
     */
    fun normalizeAudio(samples: ShortArray): FloatArray {
        if (samples.isEmpty()) return floatArrayOf()
        
        // Find max absolute value for dynamic normalization
        var maxAbs = 0
        for (sample in samples) {
            val absValue = abs(sample.toInt())
            if (absValue > maxAbs) {
                maxAbs = absValue
            }
        }
        
        // Use dynamic normalization if signal is present, otherwise use standard normalization
        val normalizationFactor = if (maxAbs > 1000) {
            // Dynamic normalization - adjusts for quieter voices
            maxAbs.toFloat()
        } else {
            // Standard normalization for very quiet signals
            Short.MAX_VALUE.toFloat()
        }
        
        return FloatArray(samples.size) { i ->
            samples[i] / normalizationFactor
        }
    }
    
    /**
     * Calculate RMS (Root Mean Square) energy of audio signal
     * Used to detect if there's actual speech vs silence
     */
    fun calculateRMS(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        
        var sum = 0.0
        for (sample in samples) {
            sum += sample * sample
        }
        return sqrt(sum / samples.size).toFloat()
    }
    
    /**
     * Apply simple gain to boost quieter audio
     * This helps detect normal speaking volume
     */
    fun applyGain(samples: FloatArray, gain: Float = 2.0f): FloatArray {
        return FloatArray(samples.size) { i ->
            val boosted = samples[i] * gain
            // Clamp to [-1.0, 1.0] to avoid clipping
            when {
                boosted > 1.0f -> 1.0f
                boosted < -1.0f -> -1.0f
                else -> boosted
            }
        }
    }
    
    /**
     * Pad or trim audio to target length
     */
    fun padOrTrim(samples: FloatArray, targetLength: Int): FloatArray {
        return when {
            samples.size == targetLength -> samples
            samples.size < targetLength -> {
                // Pad with zeros
                FloatArray(targetLength) { i ->
                    if (i < samples.size) samples[i] else 0f
                }
            }
            else -> {
                // Trim
                samples.copyOf(targetLength)
            }
        }
    }
    
    /**
     * Convert short samples to float and preprocess for model
     */
    fun preprocessForModel(
        samples: ShortArray,
        targetLength: Int,
        applyGainBoost: Boolean = true
    ): FloatArray {
        // Normalize to [-1, 1]
        var processed = normalizeAudio(samples)
        
        // Apply gain boost for normal voice sensitivity
        if (applyGainBoost) {
            processed = applyGain(processed, gain = 2.5f)
        }
        
        // Pad or trim to target length
        processed = padOrTrim(processed, targetLength)
        
        return processed
    }
    
    /**
     * Extract simple MFCC-like features for keyword spotting model
     * Returns 40 coefficients as expected by the model
     */
    fun extractMFCCFeatures(samples: ShortArray): FloatArray {
        // Normalize and boost audio
        var audio = normalizeAudio(samples)
        audio = applyGain(audio, gain = 3.0f)  // Higher gain for low voice
        
        // Ensure we have enough samples
        val targetLength = 16000  // 1 second at 16kHz
        audio = padOrTrim(audio, targetLength)
        
        // Simple MFCC approximation using FFT bins
        // This is a simplified version - TFLite support library would be better
        // but for keyword spotting, this statistical approach often works
        
        val numCoefficients = 40
        val features = FloatArray(numCoefficients)
        
        // Divide audio into frames
        val frameSize = 512
        val hopSize = 256
        val numFrames = (audio.size - frameSize) / hopSize + 1
        
        // For each MFCC coefficient, compute statistics across frames
        val frameFeatures = mutableListOf<FloatArray>()
        
        for (i in 0 until numFrames) {
            val start = i * hopSize
            val end = minOf(start + frameSize, audio.size)
            if (end - start < frameSize / 2) break
            
            val frame = audio.sliceArray(start until end)
            
            // Compute energy in different frequency bands (simplified MFCC)
            val bandEnergies = computeBandEnergies(frame, numCoefficients)
            frameFeatures.add(bandEnergies)
        }
        
        // Aggregate features across time (mean of each coefficient)
        for (i in 0 until numCoefficients) {
            var sum = 0f
            var count = 0
            for (frame in frameFeatures) {
                val value = frame[i]
                if (!value.isNaN() && !value.isInfinite()) {
                    sum += value
                    count++
                }
            }
            features[i] = if (count > 0) sum / count else 0f
        }
        
        // Final NaN check and normalization
        for (i in features.indices) {
            if (features[i].isNaN() || features[i].isInfinite()) {
                features[i] = 0f
            }
        }
        
        return features
    }
    
    /**
     * Compute energy in different frequency bands (simplified MFCC)
     */
    private fun computeBandEnergies(frame: FloatArray, numBands: Int): FloatArray {
        val energies = FloatArray(numBands)
        val bandSize = max(1, frame.size / numBands)  // Ensure bandSize >= 1
        
        for (i in 0 until numBands) {
            val start = i * bandSize
            val end = minOf(start + bandSize, frame.size)
            
            // Skip if invalid range
            if (start >= frame.size || start >= end) {
                energies[i] = -10.0f  // Safe default for log energy
                continue
            }
            
            var energy = 0f
            for (j in start until end) {
                val sample = frame[j]
                // Add NaN check
                if (sample.isNaN() || sample.isInfinite()) continue
                energy += sample * sample
            }
            
            // Ensure energy is non-negative and add epsilon for log
            val safeEnergy = max(0f, energy) + 1e-6f
            
            // Log energy (MFCC uses log scale) with NaN check
            val logEnergy = kotlin.math.ln(safeEnergy)
            energies[i] = if (logEnergy.isNaN() || logEnergy.isInfinite()) -10.0f else logEnergy
        }
        
        return energies
    }

}
