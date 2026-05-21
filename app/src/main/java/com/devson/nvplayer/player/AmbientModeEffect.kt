package com.devson.nvplayer.player

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * A Media3 GlEffect that implements a high-performance ambient glow (ambience mode).
 */
@OptIn(UnstableApi::class)
class AmbientModeEffect : GlEffect {

    private var videoWidth = 1920
    private var videoHeight = 1080
    private var surfaceWidth = 1920
    private var surfaceHeight = 1080
    private var screenAspectRatio = 16f / 9f

    fun updateDimensions(vW: Int, vH: Int, sW: Int, sH: Int) {
        if (vW > 0) videoWidth = vW
        if (vH > 0) videoHeight = vH
        if (sW > 0) surfaceWidth = sW
        if (sH > 0) surfaceHeight = sH
        if (sW > 0 && sH > 0) screenAspectRatio = sW.toFloat() / sH.toFloat()
    }

    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean,
    ): GlShaderProgram = AmbientModeShaderProgram(
        useHdr = useHdr,
        effect = this
    )

    private class AmbientModeShaderProgram(
        useHdr: Boolean,
        private val effect: AmbientModeEffect
    ) : BaseGlShaderProgram(useHdr, 1) {

        private val glProgram: GlProgram

        init {
            try {
                glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            } catch (exception: GlUtil.GlException) {
                throw VideoFrameProcessingException(exception)
            }
            
            val identityMatrix = GlUtil.create4x4IdentityMatrix()
            glProgram.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                4,
            )
            glProgram.setFloatsUniform("uTransformationMatrix", identityMatrix)
            glProgram.setFloatsUniform("uTexTransformationMatrix", identityMatrix)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            val inputAspectRatio = inputWidth.toFloat() / inputHeight
            // One-Player structure: ensure output matches screen AR
            return if (effect.screenAspectRatio > inputAspectRatio) {
                Size((inputHeight * effect.screenAspectRatio).toInt(), inputHeight)
            } else {
                Size(inputWidth, (inputWidth / effect.screenAspectRatio).toInt())
            }
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                glProgram.use()
                
                val vW = effect.videoWidth.toFloat()
                val vH = effect.videoHeight.toFloat()
                val sW = effect.surfaceWidth.toFloat()
                val sH = effect.surfaceHeight.toFloat()
                
                val vAr = vW / vH.coerceAtLeast(1f)
                val sAr = sW / sH.coerceAtLeast(1f)
                
                // One-Player mapping: divide by scale (scale = video / output)
                var sx = 1.0f
                var sy = 1.0f
                
                if (sAr > vAr) {
                    sx = vAr / sAr // video is narrower than output
                } else if (vAr > sAr) {
                    sy = sAr / vAr // video is shorter than output
                }

                glProgram.setFloatUniform("uVideoScaleX", sx)
                glProgram.setFloatUniform("uVideoScaleY", sy)
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                glProgram.bindAttributesAndUniforms()
                
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }
    }

    private companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            uniform mat4 uTransformationMatrix;
            uniform mat4 uTexTransformationMatrix;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_Position = uTransformationMatrix * aFramePosition;
              vec4 texPos = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
              vTexSamplingCoord = (uTexTransformationMatrix * texPos).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #version 100
            precision highp float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexSamplingCoord;
            uniform float uVideoScaleX;
            uniform float uVideoScaleY;

            float hash(vec2 p) {
                return fract(sin(dot(p, vec2(12.71, 31.17))) * 43758.5453);
            }

            void main() {
                vec2 uv = vTexSamplingCoord;
                // One-Player mapping logic
                vec2 video_uv = (uv - 0.5) / vec2(uVideoScaleX, uVideoScaleY) + 0.5;

                // If inside video bounds, draw the video
                if (video_uv.x >= 0.0 && video_uv.x <= 1.0 && video_uv.y >= 0.0 && video_uv.y <= 1.0) {
                    gl_FragColor = texture2D(uTexSampler, video_uv);
                    return;
                }
                
                // AMBIENT GLOW (Sample from edges for better bleeding effect)
                vec2 center_uv = clamp(video_uv, 0.0, 1.0);
                vec3 glow = vec3(0.0);
                const int samples = 12;
                float base_seed = hash(vTexSamplingCoord);
                
                for (int i = 0; i < samples; i++) {
                    float angle = float(i) * (6.283185 / float(samples));
                    float radius = 0.02 + 0.12 * hash(vec2(float(i), base_seed));
                    vec2 offset = vec2(cos(angle), sin(angle)) * radius;
                    glow += texture2D(uTexSampler, clamp(center_uv + offset, 0.0, 1.0)).rgb;
                }
                glow /= float(samples);

                // Boost vibrancy and adjust brightness (YouTube style)
                float luma = dot(glow, vec3(0.2126, 0.7152, 0.0722));
                glow = mix(vec3(luma), glow, 1.5); // 50% saturation boost
                glow *= 0.4;

                // Darken further from video edges for a smooth gradient
                float dist = length(video_uv - center_uv);
                float fade = exp(-dist * 2.8);
                glow *= fade;

                // Subtle noise to prevent banding
                float noise = hash(vTexSamplingCoord * 10.0 + base_seed) * 0.01 - 0.005;
                gl_FragColor = vec4(clamp(glow + noise, 0.0, 1.0), 1.0);
            }
        """
    }
}
