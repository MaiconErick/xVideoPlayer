// xVideoPlayer live picture adjustments. MpvPlayer.applyFilters replaces the VALUES marker below with
// const floats (brightness, contrast, saturation, highlights, blacks, whites, vignette, vig_soft, split)
// and loads the result as a new file, because vo=gpu has no shader parameters.
// `split` draws the original on the left of that x position (-1 = off).

//!HOOK MAIN
//!BIND HOOKED
//!DESC xvp-adjust
// @VALUES@
vec4 hook() {
    vec4 c = HOOKED_texOff(vec2(0.0));
    vec2 p = HOOKED_pos;
    if (p.x < split) return c;
    vec3 rgb = c.rgb;
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));

    // tone regions: blacks lift/crush the shadows, highlights the upper mids, whites the very top
    float sh = 1.0 - smoothstep(0.0, 0.45, luma);
    float hi = smoothstep(0.45, 1.0, luma);
    float wh = smoothstep(0.75, 1.0, luma);
    rgb += blacks * 0.18 * sh;
    rgb *= 1.0 + highlights * 0.45 * hi;
    rgb += whites * 0.20 * wh;

    rgb += brightness * 0.30;
    rgb = (rgb - 0.5) * (1.0 + contrast) + 0.5;
    float l2 = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    rgb = mix(vec3(l2), rgb, 1.0 + saturation);

    // smooth vignette: starts at radius (1 - vig_soft) and falls off gradually to the corners
    vec2 q = (p - 0.5) * 2.0;
    float d = length(q) / 1.4142;
    float start = 1.0 - vig_soft;
    rgb *= 1.0 - vignette * smoothstep(start * 0.9, 1.0, d);

    return vec4(clamp(rgb, 0.0, 1.0), c.a);
}
