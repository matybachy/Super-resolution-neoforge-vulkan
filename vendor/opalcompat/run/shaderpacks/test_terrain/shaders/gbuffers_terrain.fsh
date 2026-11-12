#version 120
uniform sampler2D gtexture;
varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 normal;
varying float blockId;
varying vec3 midBlock;
void main() {
	vec4 albedo = texture2D(gtexture, texcoord) * glcolor;
	if (albedo.a < 0.1) discard;
/* DRAWBUFFERS:0123 */
	gl_FragData[0] = albedo;
	gl_FragData[1] = vec4(normal * 0.5 + 0.5, 1.0);
	gl_FragData[2] = vec4(lmcoord, blockId == 1.0 ? 1.0 : 0.0, 1.0);
	gl_FragData[3] = vec4(midBlock + 0.5, 1.0);
}
