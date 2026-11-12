#version 430
// Chained to no begin pass, so it stands alone at begin's moment: every frame, 0.25 in the buffer.
layout(local_size_x = 1, local_size_y = 1) in;
const ivec3 workGroups = ivec3(1, 1, 1);
layout(std430, binding = 0) buffer Marks { float setupValue; float beginValue; float finalValue; };
void main() {
	beginValue = 0.25;
}
