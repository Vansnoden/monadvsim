import ee
import cdsapi
import os
import requests
import calendar
from dotenv import load_dotenv
import xarray as xr
import rioxarray
from rasterio.enums import Resampling


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
    # pop_img = ee.ImageCollection("WorldPop/GP/100m/pop") \
    #             .filter(ee.Filter.date('2020-01-01', '2020-12-31')) \
    #             .first().clip(region_of_interest)
    
    #  Elevation raster (from SRTM)
    elev_img = ee.Image("CGIAR/SRTM90_V4").clip(region_of_interest)

    # Building Footprints (Google Open Buildings)
    # We filter by confidence to reduce feature count and improve raster validity
    # buildings = ee.FeatureCollection("GOOGLE/Research/open-buildings/v3/polygons") \
    #               .filterBounds(region_of_interest) \
    #               .filter(ee.Filter.gte('confidence', 0.65))
    # build_img = buildings.reduceToImage(
    #     properties=['area_in_meters'], 
    #     reducer=ee.Reducer.count()
    # ).unmask(0).reproject(crs='EPSG:4326', scale=100).clip(region_of_interest)

    datasets = {
        # f"pop_density_{int(buffer_km)}_km.tiff": pop_img,
        f"elevation_{int(buffer_km)}_km.tiff": elev_img,
        # f"buildings_{int(buffer_km)}_km.tiff": build_img
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
            if resp.headers.get('Content-Type') != 'image/tiff':
                print(f"-> Error: GEE returned an error message instead of a TIFF: {resp.text}")
                continue
            with open(os.path.join(output_folder, name), 'wb') as f:
                f.write(resp.content)
            print(f"-> Successfully downloaded: {name}")
        except Exception as e:
            print(f"-> Error downloading {name}: {e}")


# Initialize Copernicus CDS API (Climate: Temp + Precipitation)
def download_historical_climate_timeseries(lat, lon, buffer_km, year, start_month, end_month, output_path):
    """
    Downloads Historical Hourly Temperature and Precipitation from ERA5-Land.
    """
    c = cdsapi.Client()

    buffer_deg = buffer_km / 111.0 # Coordinate calculation
    region_of_focus = [
        lat + buffer_deg, # North
        lon - buffer_deg, # West
        lat - buffer_deg, # South
        lon + buffer_deg  # East
    ]

    months = [str(m).zfill(2) for m in range(start_month, end_month + 1)]
        
    print(f"-> Requesting historical climate data for {year}-{months}...")
    
    try:
        c.retrieve(
            'reanalysis-era5-land',
            {
                'variable': [
                    '2m_temperature', 
                    'total_precipitation'
                ],
                'year': str(year),
                'month': months,
                'day': [str(i).zfill(2) for i in range(1, 32)],
                'time': [f"{str(i).zfill(2)}:00" for i in range(24)],
                'area': region_of_focus,
                'data_format': 'netcdf',
                'download_format': 'unarchived'
            },
            output_path
        )
        print(f"-> Historical climate data saved to: {output_path}")
        
    except Exception as e:
        print(f"-> Error during download: {e}")
        if os.path.exists(output_path):
            print(f"-> Removing failed file: {output_path}")
            os.remove(output_path)



def download_forecast_climate_timeseries(lat, lon, buffer_km, year, month_start, output_path, lead_time_months = 6):
    """
    Downloads Forecast Hourly Temperature and Precipitation from ERA5-Land.
    """
    c = cdsapi.Client()

    buffer_deg = buffer_km / 111.0 # Coordinate calculation
    region_of_focus = [
        lat + buffer_deg, # North
        lon - buffer_deg, # West
        lat - buffer_deg, # South
        lon + buffer_deg  # East
    ]
    
    lead_times = [str(i) for i in range(1, lead_time_months + 1)]
    
    print(f"-> Requesting forecast climate data from {year}-{month_start} for {lead_time_months} months...") 
        
    try:
        c.retrieve(
            'seasonal-monthly-single-levels',
            {
                'originating_centre': 'ecmwf',
                'system': '51', # SEAS5 System
                'variable': [
                    '2m_temperature', 
                    'total_precipitation'
                ],
                'year': str(year),
                'month': str(month_start).zfill(2),
                'leadtime_month': lead_times,
                'area': region_of_focus,
                'data_format': 'netcdf',
                'download_format': 'unarchived'
            },
            output_path
        )
        print(f"-> Forecast climate data saved to: {output_path}")
        
    except Exception as e:
        print(f"-> Error during download: {e}")
        if os.path.exists(output_path):
            print(f"-> Removing failed file: {output_path}")
            os.remove(output_path)






if __name__ == "__main__":
    # Dire Dawa Coordinates
    LAT, LON = 9.604134790332163, 41.8562149505558
    BUFFER = 5 #50.0 # 50km radius
    H_YEAR = 2025
    F_YEAR = 2026
    H_START_MONTH = 9
    F_START_MONTH = 1
    H_END_MONTH = 12
    DATA_DIR = "prepared_data"
    LEAD_MONTH = 6


    download_gee_data(LAT, LON, BUFFER, DATA_DIR)
    # download_historical_climate_timeseries(LAT, LON, BUFFER, 
    #                             H_YEAR, H_START_MONTH, H_END_MONTH, 
    #                             output_path=f"{DATA_DIR}/historical_climate_{H_YEAR}_{int(BUFFER)}_km_{H_START_MONTH}_{H_END_MONTH}.nc")
    
    # download_forecast_climate_timeseries(LAT, LON, BUFFER, 
    #                             F_YEAR, F_START_MONTH,
    #                             output_path=f"{DATA_DIR}/forecast_climate_{F_YEAR}_{int(BUFFER)}_km_{F_START_MONTH}_{LEAD_MONTH}.nc",
    #                             lead_time_months= LEAD_MONTH)