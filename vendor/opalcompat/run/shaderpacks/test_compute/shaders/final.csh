#version 430
layout(local_size_x = 1, local_size_y = 1) in;
const ivec3 workGroups = ivec3(1, 1, 1);
layout(std430, binding = 0) buffer Marks { float setupValue; float beginValue; float finalValue; };
void main() {
	finalValue = 0.125;
}
