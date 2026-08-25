#!/bin/bash
# =============================================================================
# merge_netcdf.sh - Merge multiple NetCDF files with different time periods
# 
# Usage: ./merge_netcdf.sh [OPTIONS]
# 
# Options:
#   -i, --input-dir DIR     Directory containing NetCDF files (default: ./nc_files)
#   -o, --output FILE       Output NetCDF file (default: merged_climate.nc)
#   -p, --pattern PATTERN   File pattern to match (default: "*.nc")
#   -v, --variables LIST    Comma-separated list of variables to keep (optional)
#   -t, --time-dim NAME     Time dimension name (default: "time")
#   -c, --combine METHOD    Combine method: "time" or "record" (default: "time")
#   -h, --help              Show this help message
#
# Examples:
#   ./merge_netcdf.sh -i ./climate_data -o merged_2020_2023.nc -p "climate_*.nc"
#   ./merge_netcdf.sh -i ./data -v "t2m,tp" -o merged.nc
#   ./merge_netcdf.sh -i ./data -t valid_time -c record
# =============================================================================

set -e  # Exit on error

# -----------------------------------------------------------------------------
# Default values
# -----------------------------------------------------------------------------
INPUT_DIR="./nc_files"
OUTPUT_FILE="merged_climate.nc"
FILE_PATTERN="*.nc"
VARIABLES=""
TIME_DIM="time"
COMBINE_METHOD="time"  # "time" or "record"
VERBOSE=false
TEMPORARY_DIR=""

# -----------------------------------------------------------------------------
# Color codes for output
# -----------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# -----------------------------------------------------------------------------
# Helper functions
# -----------------------------------------------------------------------------
info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
    exit 1
}

debug() {
    if [ "$VERBOSE" = true ]; then
        echo -e "${BLUE}[DEBUG]${NC} $1"
    fi
}

print_help() {
    cat << EOF
merge_netcdf.sh - Merge multiple NetCDF files with different time periods

Usage: $0 [OPTIONS]

Options:
  -i, --input-dir DIR     Directory containing NetCDF files (default: ./nc_files)
  -o, --output FILE       Output NetCDF file (default: merged_climate.nc)
  -p, --pattern PATTERN   File pattern to match (default: "*.nc")
  -v, --variables LIST    Comma-separated list of variables to keep (optional)
  -t, --time-dim NAME     Time dimension name (default: "time")
  -c, --combine METHOD    Combine method: "time" or "record" (default: "time")
  -V, --verbose           Enable verbose output
  -h, --help              Show this help message

Examples:
  $0 -i ./climate_data -o merged_2020_2023.nc -p "climate_*.nc"
  $0 -i ./data -v "t2m,tp" -o merged.nc
  $0 -i ./data -t valid_time -c record

Combine Methods:
  time   - Standard time concatenation (files must have time dimension)
  record - Record concatenation (treats each file as separate record)

EOF
}

# -----------------------------------------------------------------------------
# Parse command line arguments
# -----------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case $1 in
        -i|--input-dir)
            INPUT_DIR="$2"
            shift 2
            ;;
        -o|--output)
            OUTPUT_FILE="$2"
            shift 2
            ;;
        -p|--pattern)
            FILE_PATTERN="$2"
            shift 2
            ;;
        -v|--variables)
            VARIABLES="$2"
            shift 2
            ;;
        -t|--time-dim)
            TIME_DIM="$2"
            shift 2
            ;;
        -c|--combine)
            COMBINE_METHOD="$2"
            shift 2
            ;;
        -V|--verbose)
            VERBOSE=true
            shift
            ;;
        -h|--help)
            print_help
            exit 0
            ;;
        *)
            error "Unknown option: $1"
            ;;
    esac
done

# -----------------------------------------------------------------------------
# Check prerequisites
# -----------------------------------------------------------------------------
# Check if CDO is installed
if ! command -v cdo &> /dev/null; then
    error "CDO (Climate Data Operators) is not installed. Install with: apt-get install cdo (Ubuntu) or brew install cdo (Mac)"
fi

# Check if NCO is installed (for additional features)
if ! command -v ncks &> /dev/null; then
    warning "NCO tools not found. Some features may not work. Install with: apt-get install nco (Ubuntu) or brew install nco (Mac)"
fi

# -----------------------------------------------------------------------------
# Validate input
# -----------------------------------------------------------------------------
if [ ! -d "$INPUT_DIR" ]; then
    error "Input directory does not exist: $INPUT_DIR"
fi

# Find NetCDF files
cd "$INPUT_DIR"
FILES=($(ls -1 $FILE_PATTERN 2>/dev/null | sort))
cd - > /dev/null

if [ ${#FILES[@]} -eq 0 ]; then
    error "No NetCDF files found matching pattern '$FILE_PATTERN' in directory: $INPUT_DIR"
fi

info "Found ${#FILES[@]} NetCDF files to merge"
debug "Files: ${FILES[*]}"

# -----------------------------------------------------------------------------
# Create temporary directory
# -----------------------------------------------------------------------------
TEMPORARY_DIR=$(mktemp -d -t netcdf_merge_XXXXXX)
debug "Created temporary directory: $TEMPORARY_DIR"

# Cleanup function
cleanup() {
    if [ -d "$TEMPORARY_DIR" ]; then
        debug "Cleaning up temporary directory: $TEMPORARY_DIR"
        rm -rf "$TEMPORARY_DIR"
    fi
}
trap cleanup EXIT

# -----------------------------------------------------------------------------
# Function: Check if file has time dimension
# -----------------------------------------------------------------------------
has_time_dimension() {
    local file="$1"
    local dim_name="$2"
    cdo -s info "$file" 2>&1 | grep -q "$dim_name"
    return $?
}

# -----------------------------------------------------------------------------
# Function: Get time dimension size
# -----------------------------------------------------------------------------
get_time_size() {
    local file="$1"
    local dim_name="$2"
    cdo -s ntime "$file" 2>/dev/null || echo "0"
}

# -----------------------------------------------------------------------------
# Function: Sort files by time (if possible)
# -----------------------------------------------------------------------------
sort_files_by_time() {
    local sorted_files=()
    local temp_file="$TEMPORARY_DIR/sort_info.txt"
    
    for file in "${FILES[@]}"; do
        local full_path="$INPUT_DIR/$file"
        if has_time_dimension "$full_path" "$TIME_DIM"; then
            # Try to get first time value
            local time_val=$(cdo -s outputtime,1 "$full_path" 2>/dev/null | head -1)
            if [ -n "$time_val" ]; then
                echo "$time_val $file" >> "$temp_file"
            else
                echo "0000-01-01 $file" >> "$temp_file"  # Fallback
            fi
        else
            echo "0000-01-01 $file" >> "$temp_file"  # No time dimension
        fi
    done
    
    # Sort by time
    sort -k1,1 "$temp_file" | cut -d' ' -f2 > "$TEMPORARY_DIR/sorted_files.txt"
    readarray -t sorted_files < "$TEMPORARY_DIR/sorted_files.txt"
    
    echo "${sorted_files[@]}"
}

# -----------------------------------------------------------------------------
# Function: Merge files
# -----------------------------------------------------------------------------
merge_files() {
    local merge_type="$1"
    local merged_file="$2"
    local file_list="$3"
    
    info "Merging ${#file_list[@]} files using method: $merge_type"
    
    # Create a list file for CDO
    local list_file="$TEMPORARY_DIR/file_list.txt"
    printf "%s\n" "${file_list[@]}" > "$list_file"
    
    case $merge_type in
        "time")
            # Standard time concatenation
            debug "Using time concatenation"
            cdo -O mergetime "$list_file" "$merged_file" 2>/dev/null
            if [ $? -ne 0 ]; then
                error "Failed to merge files using time concatenation. Try using --combine record"
            fi
            ;;
        "record")
            # Record concatenation
            debug "Using record concatenation"
            cdo -O cat "$list_file" "$merged_file" 2>/dev/null
            if [ $? -ne 0 ]; then
                error "Failed to merge files using record concatenation"
            fi
            ;;
        *)
            error "Unknown merge method: $merge_type"
            ;;
    esac
    
    if [ -f "$merged_file" ] && [ -s "$merged_file" ]; then
        info "Successfully merged files to: $merged_file"
    else
        error "Merge produced empty or invalid file"
    fi
}

# -----------------------------------------------------------------------------
# Function: Filter variables
# -----------------------------------------------------------------------------
filter_variables() {
    local input_file="$1"
    local output_file="$2"
    local var_list="$3"
    
    if [ -n "$var_list" ]; then
        info "Filtering variables: $var_list"
        # Split variables by comma
        IFS=',' read -ra var_array <<< "$var_list"
        local var_args=""
        for var in "${var_array[@]}"; do
            var_args="$var_args -v $var"
        done
        
        # Use ncks to select variables
        if command -v ncks &> /dev/null; then
            ncks -O $var_args "$input_file" "$output_file"
        else
            # Use CDO as fallback
            warning "NCO not available, trying CDO for variable selection"
            cdo -O selvar,$var_list "$input_file" "$output_file"
        fi
        
        if [ -f "$output_file" ] && [ -s "$output_file" ]; then
            info "Filtered variables successfully"
        else
            warning "Variable filtering failed, using full file"
            cp "$input_file" "$output_file"
        fi
    else
        # No filtering, just copy
        cp "$input_file" "$output_file"
    fi
}

# -----------------------------------------------------------------------------
# Function: Check and fix time dimension
# -----------------------------------------------------------------------------
fix_time_dimension() {
    local input_file="$1"
    local output_file="$2"
    local dim_name="$3"
    
    # Check if the time dimension is valid
    if ! has_time_dimension "$input_file" "$dim_name"; then
        warning "File $input_file does not have time dimension '$dim_name'"
        # Try to add a time dimension
        if command -v ncap2 &> /dev/null; then
            info "Attempting to add time dimension..."
            ncap2 -O -s "defdim(\"$dim_name\",1);" "$input_file" "$output_file"
        else
            warning "Cannot fix time dimension - NCO tools not available"
            cp "$input_file" "$output_file"
        fi
    else
        cp "$input_file" "$output_file"
    fi
}

# -----------------------------------------------------------------------------
# Main merge logic
# -----------------------------------------------------------------------------

# Build full file paths
full_files=()
for file in "${FILES[@]}"; do
    full_files+=("$INPUT_DIR/$file")
done

# Check if we should sort files
if [ "$COMBINE_METHOD" = "time" ]; then
    info "Sorting files by time..."
    sorted_files=($(sort_files_by_time))
    full_files=()
    for file in "${sorted_files[@]}"; do
        full_files+=("$INPUT_DIR/$file")
    done
else
    info "Using existing file order for record concatenation"
fi

# Create temporary merged file
temp_merged="$TEMPORARY_DIR/merged_temp.nc"

# Merge files
merge_files "$COMBINE_METHOD" "$temp_merged" "${full_files[@]}"

# Filter variables if requested
if [ -n "$VARIABLES" ]; then
    temp_filtered="$TEMPORARY_DIR/merged_filtered.nc"
    filter_variables "$temp_merged" "$temp_filtered" "$VARIABLES"
    temp_merged="$temp_filtered"
fi

# Fix time dimension if needed
temp_fixed="$TEMPORARY_DIR/merged_fixed.nc"
fix_time_dimension "$temp_merged" "$temp_fixed" "$TIME_DIM"

# Move to final output
mv "$temp_fixed" "$OUTPUT_FILE"

# -----------------------------------------------------------------------------
# Final validation
# -----------------------------------------------------------------------------
if [ -f "$OUTPUT_FILE" ] && [ -s "$OUTPUT_FILE" ]; then
    info "✅ Merge completed successfully!"
    info "Output file: $OUTPUT_FILE"
    
    # Print file information
    info "File information:"
    if command -v ncinfo &> /dev/null; then
        ncinfo "$OUTPUT_FILE" | head -20
    else
        cdo -s sinfo "$OUTPUT_FILE" 2>/dev/null | head -20
    fi
    
    # Print time range if available
    if has_time_dimension "$OUTPUT_FILE" "$TIME_DIM"; then
        info "Time range:"
        cdo -s outputtime "$OUTPUT_FILE" 2>/dev/null | head -2 | tail -1
        cdo -s outputtime "$OUTPUT_FILE" 2>/dev/null | tail -1
    fi
    
    # Print file size
    FILE_SIZE=$(du -h "$OUTPUT_FILE" | cut -f1)
    info "File size: $FILE_SIZE"
else
    error "Output file not created successfully"
fi

# -----------------------------------------------------------------------------
# Optional: Add a merged NetCDF with time bounds
# -----------------------------------------------------------------------------
if command -v ncap2 &> /dev/null && [ "$COMBINE_METHOD" = "time" ]; then
    info "Generating time bounds..."
    temp_bounds="$TEMPORARY_DIR/with_bounds.nc"
    ncap2 -O -s 'time_bounds[time,2]=array(time, 2, $time);' "$OUTPUT_FILE" "$temp_bounds" 2>/dev/null
    if [ -f "$temp_bounds" ]; then
        mv "$temp_bounds" "$OUTPUT_FILE"
        info "Time bounds added successfully"
    fi
fi

info "Done!"
