#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Clip NetCDF/GRIB climate data to a vector boundary (Shapefile, GeoPackage, GeoJSON).
Supports single file or batch processing.

Usage examples:
  python clip_timeseries.py -s data.nc -t study_area.shp -o clipped/
  python clip_timeseries.py -s "data/*.grib" -t study_area.gpkg -o results/ -fc COUNTY -fv Siaya
"""

import os
import sys
import argparse
import glob
import traceback

import numpy as np
import pandas as pd
import xarray as xr
import rioxarray
import geopandas as gpd
from shapely.geometry import mapping
import cfgrib


def clip_era5_by_shapefile(grib_file_path, shapefile_path, filter_params=None,
                           output_dir='./clipped_data', buffer_degrees=0.5):
    """
    Clip a single GRIB/NetCDF file to features from a vector file.
    Supported vector formats: .shp, .gpkg, .geojson.
    """
    # Validate vector file format
    valid_formats = ('.shp', '.gpkg', '.geojson')
    if not shapefile_path.lower().endswith(valid_formats):
        if shapefile_path.lower().endswith(('.qgz', '.qgs')):
            raise ValueError(
                f"QGIS project file '{shapefile_path}' cannot be used directly.\n"
                "Please export the desired layer as a Shapefile (.shp), GeoPackage (.gpkg), or GeoJSON (.geojson)\n"
                "from QGIS (right‑click layer → Export → Save Features As...)."
            )
        else:
            raise ValueError(
                f"Unsupported vector file format: {shapefile_path}\n"
                f"Supported formats: {', '.join(valid_formats)}"
            )

    os.makedirs(output_dir, exist_ok=True)

    # ----------------------------------------------------------------------
    # Load and filter shapefile features
    # ----------------------------------------------------------------------
    def load_shapefile_features():
        gdf = gpd.read_file(shapefile_path)
        if gdf.crs is None:
            gdf = gdf.set_crs('EPSG:4326', allow_override=True)
        print(f"Vector file loaded: {len(gdf)} features")
        print(f"Columns: {list(gdf.columns)}")

        if filter_params is None:
            return gdf

        if isinstance(filter_params, dict):
            col = filter_params.get('column')
            val = filter_params.get('value')
            if col not in gdf.columns:
                raise ValueError(f"Column '{col}' not found")
            filtered = gdf[gdf[col] == val]
        elif isinstance(filter_params, list):
            masks = []
            for p in filter_params:
                col, val = p.get('column'), p.get('value')
                if col in gdf.columns:
                    masks.append(gdf[col] == val)
                else:
                    print(f"Warning: Column '{col}' not found, skipping")
            if not masks:
                raise ValueError("No valid filter conditions")
            combined = masks[0]
            for m in masks[1:]:
                combined = combined | m
            filtered = gdf[combined]
        else:
            raise ValueError("filter_params must be dict, list, or None")

        print(f"Filtered to {len(filtered)} feature(s)")
        return filtered

    # ----------------------------------------------------------------------
    # Process a single feature
    # ----------------------------------------------------------------------
    def process_feature(feature_gdf, feature_name):
        print(f"\nProcessing '{feature_name}'...")
        bbox = feature_gdf.total_bounds  # (minx, miny, maxx, maxy)
        lon_slice = slice(bbox[0] - buffer_degrees, bbox[2] + buffer_degrees)
        # latitude slice from high to low if data is north‑down
        lat_slice = slice(bbox[3] + buffer_degrees, bbox[1] - buffer_degrees)

        agg_methods = {'t2m': 'mean', 'tp': 'sum'}

        try:
            ds_list = cfgrib.open_datasets(
                grib_file_path,
                chunks={'time': 10},
                backend_kwargs={'errors': 'raise'}
            )
            print(f"Found {len(ds_list)} variable groups.")
        except Exception as e:
            print(f"Error opening file: {e}")
            return None

        daily_datasets = []
        for i, ds in enumerate(ds_list):
            print(f"  Group {i+1}: {list(ds.data_vars)}")
            lon_dim = 'longitude' if 'longitude' in ds.dims else 'lon'
            lat_dim = 'latitude' if 'latitude' in ds.dims else 'lat'
            ds_sel = ds.sel(**{lon_dim: lon_slice, lat_dim: lat_slice})
            ds_sel = ds_sel.rio.write_crs("EPSG:4326")

            try:
                clipped = ds_sel.rio.clip(
                    feature_gdf.geometry.apply(mapping),
                    feature_gdf.crs,
                    all_touched=True,
                    drop=True
                )
            except Exception as e:
                print(f"    Clipping error: {e}")
                continue

            for var in clipped.data_vars:
                method = agg_methods.get(var, 'mean')
                print(f"    Aggregating {var} to daily using '{method}'...")
                daily = getattr(clipped[var].resample(time='1D'), method)()
                daily_datasets.append(daily.to_dataset())

        if not daily_datasets:
            print("No data after processing.")
            return None

        merged = xr.merge(daily_datasets, compat='override', join='outer')
        print(f"Merged variables: {list(merged.data_vars)}")

        base_name = os.path.basename(grib_file_path).replace('.grib', '').replace('.grb', '')
        safe_name = feature_name.replace(' ', '_').replace('/', '_')
        output_path = os.path.join(output_dir, f"{base_name}_{safe_name}.nc")

        encoding = {var: {'zlib': True, 'complevel': 1, 'dtype': 'float32'}
                    for var in merged.data_vars}
        merged.to_netcdf(output_path, encoding=encoding, compute=True)
        print(f"Saved: {output_path}")
        return output_path

    # ----------------------------------------------------------------------
    # Main flow
    # ----------------------------------------------------------------------
    filtered_gdf = load_shapefile_features()
    if filtered_gdf.empty:
        print("No features found after filtering.")
        return []

    output_files = []
    for idx, row in filtered_gdf.iterrows():
        single = gpd.GeoDataFrame([row], geometry='geometry', crs=filtered_gdf.crs)
        # Create a meaningful feature name
        name = None
        for col in ['NAME', 'name', 'COUNTY', 'county', 'ADM2_NAME', 'ADM1_NAME', 'adm1_name']:
            if col in row.index and pd.notna(row[col]):
                name = str(row[col])
                break
        if name is None:
            name = f"feature_{idx}"
        for id_col in ['ID', 'id', 'OBJECTID', 'FID']:
            if id_col in row.index and pd.notna(row[id_col]):
                name = f"{name}_{row[id_col]}"
                break

        out = process_feature(single, name)
        if out:
            output_files.append(out)

    return output_files


def batch_clip_era5(grib_files, shapefile_path, filter_params=None,
                    output_base_dir='./clipped_data', verbose=False):
    results = {}
    for grib_file in grib_files:
        if verbose:
            print(f"\n=== Processing: {grib_file} ===")
        base_name = os.path.basename(grib_file).replace('.grib', '').replace('.grb', '')
        out_dir = os.path.join(output_base_dir, base_name)
        try:
            out_files = clip_era5_by_shapefile(
                grib_file_path=grib_file,
                shapefile_path=shapefile_path,
                filter_params=filter_params,
                output_dir=out_dir
            )
            results[grib_file] = out_files
            if verbose:
                print(f"Generated {len(out_files)} files for {base_name}")
        except Exception as e:
            print(f"Error processing {grib_file}: {e}")
            if verbose:
                traceback.print_exc()
            results[grib_file] = []
    return results


def parse_arguments():
    parser = argparse.ArgumentParser(
        description="Clip climate time series (NetCDF/GRIB) to a vector boundary.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Single file, clip to whole shapefile
  %(prog)s -s data.nc -t study_area.shp -o clipped/

  # Clip to a specific county using GeoPackage
  %(prog)s -s data.nc -t counties.gpkg -o clipped/ -fc COUNTY -fv Siaya

  # Batch process all .grib files, clip to GeoJSON boundary
  %(prog)s -s "data/*.grib" -t boundary.geojson -o results/
        """
    )
    parser.add_argument("-s", "--source", required=True,
                        help="Path or glob pattern (e.g., '*.grib') for input file(s)")
    parser.add_argument("-t", "--target", required=True,
                        help="Path to vector file (Shapefile .shp, GeoPackage .gpkg, GeoJSON .geojson)")
    parser.add_argument("-o", "--out", required=True,
                        help="Output directory")
    parser.add_argument("-fc", "--filter-column", default=None,
                        help="Column name to filter features (e.g., 'COUNTY')")
    parser.add_argument("-fv", "--filter-value", default=None,
                        help="Value in filter column to keep")
    parser.add_argument("--buffer", type=float, default=0.5,
                        help="Buffer degrees around bounding box (default 0.5)")
    parser.add_argument("-v", "--verbose", action="store_true",
                        help="Print detailed progress")
    args = parser.parse_args()

    # Validate target file extension
    valid_ext = ('.shp', '.gpkg', '.geojson')
    if not args.target.lower().endswith(valid_ext):
        if args.target.lower().endswith(('.qgz', '.qgs')):
            parser.error(
                f"QGIS project file '{args.target}' cannot be used directly.\n"
                "Please export the desired layer as a Shapefile (.shp), GeoPackage (.gpkg), or GeoJSON (.geojson)\n"
                "from QGIS (right‑click layer → Export → Save Features As...)."
            )
        else:
            parser.error(
                f"Unsupported vector file format: {args.target}\n"
                f"Supported formats: {', '.join(valid_ext)}"
            )
    return args


def expand_source_files(pattern):
    if '*' in pattern or '?' in pattern:
        return glob.glob(pattern)
    else:
        return [pattern] if os.path.isfile(pattern) else []


def main():
    args = parse_arguments()
    os.makedirs(args.out, exist_ok=True)

    source_files = expand_source_files(args.source)
    if not source_files:
        print(f"Error: No source files found matching '{args.source}'")
        sys.exit(1)

    filter_params = None
    if args.filter_column and args.filter_value:
        filter_params = [{'column': args.filter_column, 'value': args.filter_value}]
        if args.verbose:
            print(f"Filtering on {args.filter_column} == '{args.filter_value}'")

    if args.verbose:
        print(f"Found {len(source_files)} source file(s).")
        print(f"Target vector: {args.target}")
        print(f"Output base directory: {args.out}")

    batch_clip_era5(
        grib_files=source_files,
        shapefile_path=args.target,
        filter_params=filter_params,
        output_base_dir=args.out,
        verbose=args.verbose
    )

    print("\n=== Clipping completed ===")
    print(f"All outputs saved under: {args.out}")


if __name__ == "__main__":
    main()
