#version 120
// colortex0 is named so that the plan keeps it: what reads the scene is composite.csh, and the plan goes by fragment stages.
uniform sampler2D colortex0;
uniform sampler2D colortex1;
varying vec2 texcoord;
void main() {
	vec4 scene = texture2D(colortex0, texcoord);
/* DRAWBUFFERS:2 */
	gl_FragData[0] = vec4(texture2D(colortex1, texcoord).rgb + scene.rgb * 0.0, 1.0);
}
