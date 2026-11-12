#version 430
// Once per target size: leaves 0.5 in the buffer. final.fsh shows it bottom right.
layout(local_size_x = 1, local_size_y = 1) in;
const ivec3 workGroups = ivec3(1, 1, 1);
layout(std430, binding = 0) buffer Marks { float setupValue; float beginValue; float finalValue; };
void main() {
	setupValue = 0.5;
}
