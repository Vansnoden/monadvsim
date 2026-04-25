#!/usr/bin/env python3
"""
merge_rasters_gdal.py

Merge multiple raster files into a single GeoTIFF with BIGTIFF support for large outputs.
"""

import os
import sys
import glob
import argparse
from osgeo import gdal

def merge_raster_gdal(input_paths, output_path, compress='LZW', bigtiff='YES'):
    """
    Merge rasters using GDAL's Warp function.

    Args:
        input_paths: List of input file paths.
        output_path: Output GeoTIFF path.
        compress: Compression method (LZW, DEFLATE, etc.).
        bigtiff: 'YES' or 'NO' to enable/disable BIGTIFF.
    """
    # Enable exceptions to catch GDAL errors
    gdal.UseExceptions()

    # Validate inputs
    existing = [f for f in input_paths if os.path.isfile(f)]
    if not existing:
        raise FileNotFoundError("No valid input files found.")

    # Create in-memory VRT
    vrt_path = '/vsimem/temp_merge.vrt'
    gdal.BuildVRT(vrt_path, existing, options=gdal.BuildVRTOptions(resampleAlg='near'))

    # Warp options: add BIGTIFF and compression
    creation_options = [f'COMPRESS={compress}', 'BIGTIFF=YES' if bigtiff == 'YES' else 'BIGTIFF=NO']
    warp_options = gdal.WarpOptions(format='GTiff', creationOptions=creation_options)

    # Execute warp with progress callback
    gdal.Warp(output_path, vrt_path, options=warp_options, callback=gdal.TermProgress)

    # Cleanup
    gdal.Unlink(vrt_path)
    print(f"\nSuccessfully merged {len(existing)} files into {output_path}")

def main():
    parser = argparse.ArgumentParser(description="Merge raster tiles into a GeoTIFF mosaic.")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('-i', '--input_dir', help='Directory containing tiles')
    group.add_argument('-f', '--files', nargs='+', help='List of tile paths')

    parser.add_argument('-o', '--output', required=True, help='Output GeoTIFF path')
    parser.add_argument('-p', '--pattern', default='*.tif', help='File pattern (with -i)')
    parser.add_argument('--compress', default='LZW', help='Compression (LZW, DEFLATE, etc.)')
    parser.add_argument('--bigtiff', default='YES', choices=['YES', 'NO'],
                        help='Enable BIGTIFF for files >4GB (default: YES)')

    args = parser.parse_args()

    # Collect input files
    if args.input_dir:
        pattern = os.path.join(args.input_dir, args.pattern)
        files = sorted(glob.glob(pattern))
        if not files:
            raise FileNotFoundError(f"No files matching {pattern}")
    else:
        files = args.files
        missing = [f for f in files if not os.path.isfile(f)]
        if missing:
            raise FileNotFoundError(f"Missing files: {missing}")

    try:
        merge_raster_gdal(files, args.output, args.compress, args.bigtiff)
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == '__main__':
    main()
