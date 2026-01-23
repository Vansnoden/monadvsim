
import ee
import cdsapi
import os

# 1. Initialize Google Earth Engine (for Population)
ee.Authenticate()
ee.Initialize(project='gen-lang-client-0359288668')

def download_population(lat, lon, buffer_degree, output_path):
    """Downloads WorldPop 100m population density clipped to a ROI."""
    # Define the bounding box (Region of Interest)
    roi = ee.Geometry.Rectangle([lon - buffer_degree, lat - buffer_degree, 
                                 lon + buffer_degree, lat + buffer_degree])
    
    # Load WorldPop dataset
    dataset = ee.ImageCollection("WorldPop/GP/100m/pop") \
                .filter(ee.Filter.date('2020-01-01', '2020-12-31')) \
                .first()
    
    # Clip to your specific ROI
    clipped = dataset.clip(roi)
    
    try:
        # THE FIX: Add 'scale' and 'region' explicitly
        url = clipped.getDownloadURL({
            'scale': 100,             # 100 meters per pixel
            'crs': 'EPSG:4326',       # Standard WGS84
            'region': roi.toGeoJSONString(), # CRITICAL: Limit the request to your ROI
            'format': 'GEO_TIFF'
        })
        print(f"✅ Success! Download your population data here:\n{url}")
    except Exception as e:
        print(f"❌ Error: {e}")
        

# 2. Initialize Copernicus CDS API (for Temperature Time-Series)
def download_climate(lat, lon, buffer_degree, year, month):
    """Downloads hourly ERA5 temperature data for the region."""
    c = cdsapi.Client()
    
    # Define area [North, West, South, East]
    area = [lat + buffer_degree, lon - buffer_degree, 
            lat - buffer_degree, lon + buffer_degree]
    
    c.retrieve(
        'reanalysis-era5-single-levels',
        {
            'product_type': 'reanalysis',
            'format': 'netcdf', # NetCDF is easier for time-series
            'variable': '2m_temperature',
            'year': str(year),
            'month': str(month).zfill(2),
            'day': [str(i).zfill(2) for i in range(1, 32)],
            'time': [f"{str(i).zfill(2)}:00" for i in range(24)],
            'area': area,
        },
        f'climate_{year}_{month}.nc'
    )

# --- EXECUTION ---
# Addis Ababa Coordinates
TARGET_LAT = 8.9806
TARGET_LON = 38.7578
BUFFER = 0.05 # Approx 5km radius

download_population(TARGET_LAT, TARGET_LON, BUFFER, "pop_addis.tif")
download_climate(TARGET_LAT, TARGET_LON, BUFFER, 2026, 1)