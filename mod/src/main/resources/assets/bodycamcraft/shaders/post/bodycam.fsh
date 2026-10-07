#version 330

#moj_import <minecraft:globals.glsl>

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

// Values come from the "lens" row of the camera sheet.
layout(std140) uniform BodycamConfig {
    float Fisheye;
    float Vignette;
    float Fringe;
    float Grain;
    float Desaturate;
    float Contrast;
};

out vec4 fragColor;

// Barrel distortion: the middle of the picture is magnified and the edges are squeezed in, as a wide chest
// camera does. Samples that fall outside the picture are black, which gives the dark corners.
vec3 lens(vec2 centred, float strength) {
    float r2 = dot(centred, centred);
    vec2 uv = centred * (1.0 + strength * r2) / (1.0 + strength) * 0.5 + 0.5;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        return vec3(0.0);
    }
    return texture(InSampler, uv).rgb;
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec2 centred = texCoord * 2.0 - 1.0;
    float aspect = OutSize.x / OutSize.y;
    vec2 round = vec2(centred.x, centred.y / aspect);
    float edge = dot(round, round);

    // Colour fringing grows towards the edges: each channel bends a little differently.
    vec3 colour = vec3(
        lens(centred, Fisheye + Fringe * 6.0).r,
        lens(centred, Fisheye).g,
        lens(centred, Fisheye - Fringe * 6.0).b);

    float grey = dot(colour, vec3(0.299, 0.587, 0.114));
    colour = mix(colour, vec3(grey), Desaturate);
    colour = (colour - 0.5) * Contrast + 0.5;

    float grain = hash(floor(texCoord * OutSize * 0.5) + fract(GameTime * 1200.0) * 71.0) - 0.5;
    colour += grain * Grain;

    colour *= 1.0 - Vignette * smoothstep(0.35, 1.25, edge);
    fragColor = vec4(clamp(colour, 0.0, 1.0), 1.0);
}
