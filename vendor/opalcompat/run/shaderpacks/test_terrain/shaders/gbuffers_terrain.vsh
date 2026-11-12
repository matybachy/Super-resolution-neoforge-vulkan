#version 120
// test_terrain: albedo, normal, lightmap and block id from the terrain mesh.
attribute vec4 mc_Entity;
attribute vec4 at_midBlock;
varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 normal;
varying float blockId;
varying vec3 midBlock;
void main() {
	gl_Position = ftransform();
	texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
	lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
	glcolor = gl_Color;
	normal = normalize(gl_NormalMatrix * gl_Normal);
	blockId = mc_Entity.x;
	midBlock = at_midBlock.xyz / 64.0;
}
