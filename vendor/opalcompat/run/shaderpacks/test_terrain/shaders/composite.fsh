#version 120
// test_terrain: quarters from left: albedo | view-space normal | lightmap (r=block, g=sky, b=ice id) | at_midBlock.
// Top bar (5%): r = sunAngle, g = worldTime / 24000, b = normalize(sunPosition).y * 0.5 + 0.5.
uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex2;
uniform sampler2D colortex3;
uniform float sunAngle;
uniform int worldTime;
uniform vec3 sunPosition;
varying vec2 texcoord;
void main() {
	vec3 o;
	if (texcoord.y > 0.95) o = vec3(sunAngle, float(worldTime) / 24000.0, normalize(sunPosition).y * 0.5 + 0.5);
	else if (texcoord.x < 0.25) o = texture2D(colortex0, texcoord).rgb;
	else if (texcoord.x < 0.5) o = texture2D(colortex1, texcoord).rgb;
	else if (texcoord.x < 0.75) o = texture2D(colortex2, texcoord).rgb;
	else o = texture2D(colortex3, texcoord).rgb;
/* DRAWBUFFERS:0 */
	gl_FragData[0] = vec4(o, 1.0);
}
