import ee
import cdsapi
import os
import requests

# 1. Initialize Google Earth Engine
# Ensure you have run 'earthengine authenticate' in your terminal
ee.Initialize(project='gen-lang-client-0359288668')

def download_gee_data(lat, lon, buffer_km, output_folder):
    """Downloads Population, Elevation, and Building Density from GEE."""
    os.makedirs(output_folder, exist_ok=True)
    
    # Convert km to degrees (approximate)
    buffer_deg = buffer_km / 111.0
    roi = ee.Geometry.Rectangle([lon - buffer_deg, lat - buffer_deg, 
                                 lon + buffer_deg, lat + buffer_deg])

    # --- A. Population (WorldPop) ---
    pop_img = ee.ImageCollection("WorldPop/GP/100m/pop") \
                .filter(ee.Filter.date('2020-01-01', '2020-12-31')) \
                .first().clip(roi)
    
    # --- B. Elevation (SRTM) ---
    elev_img = ee.Image("CGIAR/SRTM90_V4").clip(roi)

    # --- C. Building Footprints (Google Open Buildings) ---
    # We convert vector footprints to a density raster for the simulation
    buildings = ee.FeatureCollection("GOOGLE/Research/open-buildings/v3/polygons") \
                  .filterBounds(roi)
    build_img = buildings.reduceToImage(properties=['area_in_meters'], reducer=ee.Reducer.count()) \
                         .unmask(0).clip(roi)

    datasets = {
        "pop_addis.tiff": pop_img,
        "elev_addis.tiff": elev_img,
        "buildings_addis.tiff": build_img
    }

    for name, img in datasets.items():
        try:
            url = img.getDownloadURL({
                'scale': 100,
                'crs': 'EPSG:4326',
                'region': roi.toGeoJSONString(),
                'format': 'GEO_TIFF'
            })
            resp = requests.get(url)
            with open(os.path.join(output_folder, name), 'wb') as f:
                f.write(resp.content)
            print(f"✅ Downloaded: {name}")
        except Exception as e:
            print(f"❌ Error downloading {name}: {e}")

# 2. Initialize Copernicus CDS API (Climate: Temp + Precipitation)
def download_climate_timeseries(lat, lon, buffer_km, year, month, output_path):
    """Downloads Hourly Temperature and Precipitation from ERA5-Land."""
    c = cdsapi.Client()
    buffer_deg = buffer_km / 111.0
    
    # Area: [North, West, South, East]
    area = [lat + buffer_deg, lon - buffer_deg, 
            lat - buffer_deg, lon + buffer_deg]
    
    print(f"📡 Requesting climate data for {year}-{month}...")
    c.retrieve(
        'reanalysis-era5-land',
        {
            'variable': [
                '2m_temperature',
                'total_precipitation', # Added Rainfall
            ],
            'year': str(year),
            'month': str(month).zfill(2),
            'day': [str(i).zfill(2) for i in range(1, 32)],
            'time': [f"{str(i).zfill(2)}:00" for i in range(24)],
            'area': area,
            'format': 'netcdf',
        },
        output_path
    )
    print(f"✅ Climate data saved to: {output_path}")

if __name__ == "__main__":
    # Addis Ababa Coordinates
    LAT, LON = 9.03, 38.75
    BUFFER = 5.0 # 5km radius
    DATA_DIR = "prepared_data"

    # Step 1: Static Rasters
    download_gee_data(LAT, LON, BUFFER, DATA_DIR)

    # Step 2: Time-series Climate
    download_climate_timeseries(LAT, LON, BUFFER, 2024, 1, f"{DATA_DIR}/climate_2024_01.nc")