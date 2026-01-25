import ee
import cdsapi
import os
import requests
import calendar
from dotenv import load_dotenv


load_dotenv()

EE_PROJECT_ID = os.environ.get('EE_PROJECT_ID')
ee.Initialize(project=EE_PROJECT_ID) # initialize google earth engine (GEE)


def download_gee_data(lat, lon, buffer_km, output_folder):
    """Downloads Population, Elevation, and Building Density from GEE."""

    os.makedirs(output_folder, exist_ok=True)
    buffer_deg = buffer_km / 111.0 # Convert km to degrees (approximate)
    region_of_interest = ee.Geometry.Rectangle([lon - buffer_deg, lat - buffer_deg, 
                                 lon + buffer_deg, lat + buffer_deg])

    # Population density ( from WorldPop)
    pop_img = ee.ImageCollection("WorldPop/GP/100m/pop") \
                .filter(ee.Filter.date('2020-01-01', '2020-12-31')) \
                .first().clip(region_of_interest)
    
    #  Elevation raster (from SRTM)
    elev_img = ee.Image("CGIAR/SRTM90_V4").clip(region_of_interest)

    # Building Footprints (Google Open Buildings)
    # We convert vector footprints to a density raster for the simulation
    buildings = ee.FeatureCollection("GOOGLE/Research/open-buildings/v3/polygons") \
                  .filterBounds(region_of_interest)
    build_img = buildings.reduceToImage(properties=['area_in_meters'], reducer=ee.Reducer.count()) \
                         .unmask(0).clip(region_of_interest)

    datasets = {
        f"pop_density_{int(buffer_km)}_km.tiff": pop_img,
        f"elevation_{int(buffer_km)}_km.tiff": elev_img,
        f"buildings_{int(buffer_km)}_km.tiff": build_img
    }

    for name, img in datasets.items():
        try:
            url = img.getDownloadURL({
                'scale': 100,
                'crs': 'EPSG:4326',
                'region': region_of_interest.toGeoJSONString(),
                'format': 'GEO_TIFF'
            })
            resp = requests.get(url)
            with open(os.path.join(output_folder, name), 'wb') as f:
                f.write(resp.content)
            print(f"-> Successfully downloaded: {name}")
        except Exception as e:
            print(f"-> Error downloading {name}: {e}")


# Initialize Copernicus CDS API (Climate: Temp + Precipitation)
def download_climate_timeseries(lat, lon, buffer_km, year, month, output_path):
    """
    Downloads Hourly Temperature and Precipitation from ERA5-Land.
    """
    c = cdsapi.Client()

    buffer_deg = buffer_km / 111.0 # Coordinate calculation
    region_of_focus = [
        lat + buffer_deg, # North
        lon - buffer_deg, # West
        lat - buffer_deg, # South
        lon + buffer_deg  # East
    ]
    
    last_day = calendar.monthrange(year, month)[1]
    days = [str(i).zfill(2) for i in range(1, last_day + 1)]
    
    print(f"-> Requesting climate data for {year}-{month}...")
    
    try:
        c.retrieve(
            'reanalysis-era5-land',
            {
                'variable': [
                    '2m_temperature', 
                    'total_precipitation'
                ],
                'year': str(year),
                'month': str(month).zfill(2),
                'day': days,
                'time': [f"{str(i).zfill(2)}:00" for i in range(24)],
                'area': region_of_focus,
                'data_format': 'netcdf',
                'download_format': 'unarchived'
            },
            output_path
        )
        print(f"-> Climate data saved to: {output_path}")
        
    except Exception as e:
        print(f"-> Error during download: {e}")
        if os.path.exists(output_path):
            print(f"-> Removing failed file: {output_path}")
            os.remove(output_path)


if __name__ == "__main__":
    # Addis Ababa Coordinates
    LAT, LON = 9.02650000, 38.73119444
    BUFFER = 50.0 # 50km radius
    YEAR = 2023
    MONTH = 6
    DATA_DIR = "prepared_data"


    download_gee_data(LAT, LON, BUFFER, DATA_DIR)
    download_climate_timeseries(LAT, LON, BUFFER, 
                                YEAR, MONTH, 
                                output_path=f"{DATA_DIR}/climate_{YEAR}_{int(BUFFER)}_km_{MONTH}.nc")