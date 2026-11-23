package com.canonmulticam.pro.gl

object ShaderToolFlags {
    const val LUT = 1 shl 0
    const val PEAKING = 1 shl 1
    const val FALSE_COLOR = 1 shl 2
    const val ZEBRA = 1 shl 3
    const val GUIDE_9_16 = 1 shl 4
    const val GUIDE_2_39 = 1 shl 5
    const val GUIDE_THIRDS = 1 shl 6
}

object ShaderVariantCompiler {

    val VERTEX_SHADER = """
        #version 300 es
        layout(location = 0) in vec4 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        out vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    fun buildFragmentShader(flags: Int): String {
        val defines = buildString {
            appendLine("#version 300 es")
            appendLine("precision highp float;")
            appendLine("precision highp sampler3D;")
            if ((flags and ShaderToolFlags.LUT) != 0) appendLine("#define USE_LUT")
            if ((flags and ShaderToolFlags.PEAKING) != 0) appendLine("#define USE_PEAKING")
            if ((flags and ShaderToolFlags.FALSE_COLOR) != 0) appendLine("#define USE_FALSE_COLOR")
            if ((flags and ShaderToolFlags.ZEBRA) != 0) appendLine("#define USE_ZEBRA")
            if ((flags and ShaderToolFlags.GUIDE_9_16) != 0) appendLine("#define USE_GUIDE_9_16")
            if ((flags and ShaderToolFlags.GUIDE_2_39) != 0) appendLine("#define USE_GUIDE_2_39")
            if ((flags and ShaderToolFlags.GUIDE_THIRDS) != 0) appendLine("#define USE_GUIDE_THIRDS")
        }

        return defines + """
            in vec2 vTexCoord;
            out vec4 fragColor;

            uniform sampler2D uTexture;
            uniform sampler3D uLutTex;
            uniform int uHasLutTexture;
            uniform vec3 uPeakingColor;
            uniform vec2 uVideoTexelSize;
            uniform vec2 uScreenTexelSize;
            uniform float uTime;

            float luma(vec3 c) {
                return dot(c, vec3(0.2126, 0.7152, 0.0722));
            }

            void main() {
                vec3 color = texture(uTexture, vTexCoord).rgb;

                #ifdef USE_LUT
                    if (uHasLutTexture == 1) {
                        color = texture(uLutTex, clamp(color, 0.0, 1.0)).rgb;
                    }
                #endif

                #ifdef USE_FALSE_COLOR
                    float y = luma(color);
                    if (y < 0.05) color = vec3(0.25, 0.0, 0.6);
                    else if (y < 0.15) color = vec3(0.0, 0.35, 1.0);
                    else if (y > 0.42 && y < 0.52) color = vec3(0.0, 0.85, 0.25);
                    else if (y > 0.65 && y < 0.74) color = vec3(0.95, 0.55, 0.65);
                    else if (y > 0.94) color = vec3(1.0, 0.0, 0.0);
                    else color = vec3(y);
                #endif

                #ifdef USE_PEAKING
                    float c0 = luma(texture(uTexture, vTexCoord).rgb);
                    float cx = luma(texture(uTexture, vTexCoord + vec2(uVideoTexelSize.x, 0.0)).rgb);
                    float cy = luma(texture(uTexture, vTexCoord + vec2(0.0, uVideoTexelSize.y)).rgb);
                    float edge = length(vec2(cx - c0, cy - c0));
                    if (edge > 0.13) {
                        color = mix(color, uPeakingColor, 0.85);
                    }
                #endif

                #ifdef USE_ZEBRA
                    float lum = luma(color);
                    if (lum > 0.92) {
                        float stripe = sin((gl_FragCoord.x + gl_FragCoord.y) * 0.65 + uTime * 8.0);
                        if (stripe > 0.0) color = vec3(1.0, 0.9, 0.0);
                    }
                #endif

                #ifdef USE_GUIDE_9_16
                    float distLeft = abs(vTexCoord.x - 0.3418);
                    float distRight = abs(vTexCoord.x - 0.6582);
                    if (distLeft < uScreenTexelSize.x * 1.5 || distRight < uScreenTexelSize.x * 1.5) {
                        color = mix(color, vec3(0.65, 0.55, 0.38), 0.9);
                    } else if (vTexCoord.x < 0.3418 || vTexCoord.x > 0.6582) {
                        color *= 0.45;
                    }
                #endif

                #ifdef USE_GUIDE_2_39
                    float distTop = abs(vTexCoord.y - 0.128);
                    float distBot = abs(vTexCoord.y - 0.872);
                    if (distTop < uScreenTexelSize.y * 1.5 || distBot < uScreenTexelSize.y * 1.5) {
                        color = mix(color, vec3(0.96, 0.95, 0.93), 0.85);
                    } else if (vTexCoord.y < 0.128 || vTexCoord.y > 0.872) {
                        color *= 0.35;
                    }
                #endif

                #ifdef USE_GUIDE_THIRDS
                    float tX1 = abs(vTexCoord.x - 0.3333);
                    float tX2 = abs(vTexCoord.x - 0.6667);
                    float tY1 = abs(vTexCoord.y - 0.3333);
                    float tY2 = abs(vTexCoord.y - 0.6667);
                    bool onGrid = (tX1 < uScreenTexelSize.x || tX2 < uScreenTexelSize.x ||
                                   tY1 < uScreenTexelSize.y || tY2 < uScreenTexelSize.y);
                    float cDx = abs(vTexCoord.x - 0.5);
                    float cDy = abs(vTexCoord.y - 0.5);
                    bool onCross = (cDx < uScreenTexelSize.x * 1.5 && cDy < uScreenTexelSize.y * 14.0) ||
                                   (cDy < uScreenTexelSize.y * 1.5 && cDx < uScreenTexelSize.x * 14.0);
                    if (onGrid || onCross) {
                        color = mix(color, vec3(0.96, 0.95, 0.93), 0.55);
                    }
                #endif

                fragColor = vec4(color, 1.0);
            }
        """.trimIndent()
    }
}
