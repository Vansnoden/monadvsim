#!/usr/bin/env python3
"""
tick_to_datetime.py - Convert tick IDs to datetime fields in simulation CSV files

Reads simulation parameters (start date, tick minutes) from simulation.yaml config file.

Usage:
    python tick_to_datetime.py -f <result-files> [-o <output-folder>] [-c <config-file>]

Arguments:
    -f, --file      Path to the CSV file(s) to process (supports wildcards)
    -o, --output    Output folder (optional, defaults to the input file's directory)
    -c, --config    Path to simulation.yaml config file (default: src/main/resources/config/simulation.yaml)
    -h, --help      Show this help message

Example:
    python tick_to_datetime.py -f results/merged_snapshots_*.csv
    python tick_to_datetime.py -f results/snapshot_*.csv -o results/processed
    python tick_to_datetime.py -f results.csv -c config/simulation.yaml
"""

import argparse
import csv
import glob
import os
import sys
import yaml
from datetime import datetime, timedelta
from pathlib import Path


def load_config(config_path):
    """
    Load simulation configuration from YAML file.
    
    Args:
        config_path: Path to the simulation.yaml file
    
    Returns:
        Tuple of (start_date, tick_minutes)
    """
    # Try multiple possible locations
    possible_paths = [
        config_path,
        "src/main/resources/config/simulation.yaml",
        "config/simulation.yaml",
        "simulation.yaml",
        "../config/simulation.yaml",
        "../../config/simulation.yaml",
    ]
    
    # If config_path is provided, try it first
    if config_path:
        possible_paths.insert(0, config_path)
    
    for path in possible_paths:
        if os.path.exists(path):
            try:
                with open(path, 'r', encoding='utf-8') as f:
                    config = yaml.safe_load(f)
                
                # Extract time configuration
                time_config = config.get('time', {})
                start_date_str = time_config.get('startDateTime')
                tick_minutes = time_config.get('tickMinutes', 15)
                
                if not start_date_str:
                    print(f"Warning: No startDateTime found in {path}. Using default 2020-01-01 00:00:00")
                    start_date = datetime(2020, 1, 1, 0, 0, 0)
                else:
                    # Parse start date (supports ISO format with or without timezone)
                    try:
                        # Try with time
                        start_date = datetime.fromisoformat(start_date_str.replace('Z', '+00:00'))
                        # Remove timezone info for simplicity
                        start_date = start_date.replace(tzinfo=None)
                    except ValueError:
                        try:
                            # Try date only
                            start_date = datetime.strptime(start_date_str, "%Y-%m-%d")
                        except ValueError:
                            # Try with T separator
                            start_date = datetime.strptime(start_date_str.split('T')[0] + " " + start_date_str.split('T')[1].split('+')[0], "%Y-%m-%d %H:%M:%S")
                
                print(f"Loaded config from: {path}")
                print(f"  Start date: {start_date.strftime('%Y-%m-%d %H:%M:%S')}")
                print(f"  Tick minutes: {tick_minutes}")
                return start_date, tick_minutes
                
            except Exception as e:
                print(f"Warning: Error loading config from {path}: {e}")
                continue
    
    # If no config found, use defaults
    print("Warning: No config file found. Using default values.")
    print("  Start date: 2020-01-01 00:00:00")
    print("  Tick minutes: 15")
    return datetime(2020, 1, 1, 0, 0, 0), 15


def parse_tick_to_datetime(tick_id, start_date, tick_minutes):
    """
    Convert a tick ID to a datetime object.
    
    Args:
        tick_id: Integer tick number
        start_date: Simulation start datetime
        tick_minutes: Minutes per tick
    
    Returns:
        datetime object representing the tick's timestamp
    """
    delta_minutes = int(tick_id) * tick_minutes
    return start_date + timedelta(minutes=delta_minutes)


def process_csv_file(input_file, output_folder, start_date, tick_minutes):
    """
    Process a single CSV file, adding datetime fields for each tick ID.
    
    Args:
        input_file: Path to the input CSV file
        output_folder: Output folder
        start_date: Simulation start datetime
        tick_minutes: Minutes per tick
    
    Returns:
        Path to the output file, or None if processing failed
    """
    try:
        # Determine output path
        input_path = Path(input_file)
        output_folder = Path(output_folder)
        output_folder.mkdir(parents=True, exist_ok=True)
        
        # Generate output filename with timestamp
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        output_filename = f"sim_data_{timestamp}.csv"
        output_path = output_folder / output_filename
        
        print(f"Processing: {input_file}")
        print(f"Output: {output_path}")
        
        # Read and process CSV
        rows_processed = 0
        rows_with_tick = 0
        
        with open(input_file, 'r', encoding='utf-8') as infile:
            # Detect dialect
            sample = infile.read(1024)
            infile.seek(0)
            sniffer = csv.Sniffer()
            try:
                dialect = sniffer.sniff(sample)
                has_header = sniffer.has_header(sample)
            except csv.Error:
                dialect = csv.excel()
                has_header = True
            
            reader = csv.reader(infile, dialect)
            
            # Read header
            if has_header:
                header = next(reader)
            else:
                # If no header, assume standard format
                header = ['TickCount', 'AgentID', 'Layer', 'AgentType', 'X', 'Y', 'Alive', 
                         'Age', 'Stage', 'Energy', 'Gravid', 'EggCount', 'LarvaCount', 
                         'waterVolume', 'Resting', 'RestingDuration', 'TimeWithoutRest']
            
            # Find TickCount column index
            tick_col_index = None
            for idx, col in enumerate(header):
                col_lower = col.lower().strip()
                if col_lower in ['tickcount', 'tick', 'tick_count', 'tickid', 'tick_id']:
                    tick_col_index = idx
                    break
            
            if tick_col_index is None:
                # Try case-insensitive search
                for idx, col in enumerate(header):
                    if 'tick' in col.lower() and 'count' in col.lower():
                        tick_col_index = idx
                        break
                
                if tick_col_index is None:
                    print(f"Warning: No TickCount column found in {input_file}. Skipping.")
                    return None
            
            # Build new header with datetime fields
            new_header = header.copy()
            new_header.append('DateTime')
            new_header.append('DateTimeISO')
            
            # Read all rows
            rows = list(reader)
            
            # Process rows
            processed_rows = []
            for row in rows:
                # Skip empty rows
                if not row or all(cell == '' for cell in row):
                    continue
                
                # Ensure row has enough columns
                while len(row) < len(header):
                    row.append('')
                
                # Get tick value
                tick_value = row[tick_col_index].strip() if tick_col_index < len(row) else ''
                if tick_value:
                    try:
                        tick_id = int(float(tick_value))
                        dt = parse_tick_to_datetime(tick_id, start_date, tick_minutes)
                        dt_str = dt.strftime("%Y-%m-%d %H:%M:%S")
                        dt_iso = dt.isoformat()
                        rows_with_tick += 1
                    except (ValueError, TypeError):
                        dt_str = ''
                        dt_iso = ''
                else:
                    dt_str = ''
                    dt_iso = ''
                
                # Add datetime fields
                row.append(dt_str)
                row.append(dt_iso)
                processed_rows.append(row)
                rows_processed += 1
            
            # Write output file
            with open(output_path, 'w', encoding='utf-8', newline='') as outfile:
                writer = csv.writer(outfile, dialect)
                writer.writerow(new_header)
                writer.writerows(processed_rows)
            
            print(f"✓ Processed {rows_processed} rows ({rows_with_tick} with tick values)")
            return output_path
            
    except Exception as e:
        print(f"✗ Error processing {input_file}: {e}")
        import traceback
        traceback.print_exc()
        return None


def main():
    parser = argparse.ArgumentParser(
        description="Convert tick IDs to datetime fields in simulation CSV files",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s -f results/merged_snapshots_*.csv
  %(prog)s -f results/snapshot_*.csv -o results/processed
  %(prog)s -f results.csv -c config/simulation.yaml
        """
    )
    
    parser.add_argument(
        '-f', '--file',
        required=True,
        help='Path to CSV file(s) to process (supports wildcards)'
    )
    
    parser.add_argument(
        '-o', '--output',
        help='Output folder (default: input file\'s directory)'
    )
    
    parser.add_argument(
        '-c', '--config',
        default='src/main/resources/config/simulation.yaml',
        help='Path to simulation.yaml config file (default: src/main/resources/config/simulation.yaml)'
    )
    
    args = parser.parse_args()
    
    # Load configuration
    start_date, tick_minutes = load_config(args.config)
    
    # Expand wildcards
    if '*' in args.file or '?' in args.file:
        input_files = glob.glob(args.file)
        if not input_files:
            print(f"Error: No files found matching pattern: {args.file}")
            sys.exit(1)
    else:
        # Check if it's a directory or file
        if os.path.isdir(args.file):
            # Process all CSV files in directory
            input_files = glob.glob(os.path.join(args.file, '*.csv'))
            if not input_files:
                print(f"Error: No CSV files found in directory: {args.file}")
                sys.exit(1)
        else:
            input_files = [args.file]
    
    # Filter for CSV files
    input_files = [f for f in input_files if f.lower().endswith('.csv')]
    
    if not input_files:
        print("Error: No CSV files found to process")
        sys.exit(1)
    
    # Determine output folder
    if args.output:
        output_folder = args.output
    else:
        # Use the directory of the first input file
        output_folder = str(Path(input_files[0]).parent)
    
    print(f"Found {len(input_files)} CSV file(s) to process")
    print(f"Start date: {start_date.strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"Tick minutes: {tick_minutes}")
    print(f"Output folder: {output_folder}")
    print("-" * 60)
    
    # Process each file
    output_files = []
    for input_file in input_files:
        output_path = process_csv_file(
            input_file,
            output_folder,
            start_date,
            tick_minutes
        )
        if output_path:
            output_files.append(output_path)
    
    # Summary
    print("-" * 60)
    if output_files:
        print(f"✅ Successfully processed {len(output_files)} file(s):")
        for f in output_files:
            print(f"  - {f}")
    else:
        print("❌ No files were successfully processed")
        sys.exit(1)


if __name__ == '__main__':
    main()
