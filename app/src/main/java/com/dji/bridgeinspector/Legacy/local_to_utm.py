import numpy as np
import sys

def local_to_utm(input_file):
    # Initial gps coordinate, in utm format
    translation_vector = np.array([395535.05, 4441177.50, 194.46])
    output_file = 'output.obj'

    with open(input_file, 'r') as infile, open(output_file, 'w') as outfile:
        for line in infile:
            # Check if the line defines a vertex
            if line.startswith('v '):
                parts = line.split()
                # apply transformation to local coordinates
                local_coord = np.array([float(parts[1]), float(parts[2]), float(parts[3])])
                utm_coord = local_coord + translation_vector

                # Overwrite current line
                outfile.write(f"v {utm_coord[0]} {utm_coord[1]} {utm_coord[2]}\n")
            else:
                # Copy all other lines directly
                outfile.write(line)

    print(f"Successfully converted model: {output_file}")

    if __name__ == "__main__":
        input_file = sys.argv[1]
        local_to_utm(input_file)
