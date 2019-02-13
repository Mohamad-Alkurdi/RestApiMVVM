package com.codingwithmitch.foodrecipes.requests;


import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.MediatorLiveData;
import android.arch.lifecycle.Observer;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;

import com.codingwithmitch.foodrecipes.AppExecutors;
import com.codingwithmitch.foodrecipes.requests.responses.ApiResponse;

import java.io.IOException;

import retrofit2.Call;
import retrofit2.Response;


// ResultType: Type for the Resource data.
// RequestType: Type for the API response.
public abstract class NetworkBoundResource<ResultType, RequestType> {

    private AppExecutors appExecutors;
    private MediatorLiveData<Resource<ResultType>> result = new MediatorLiveData<>();

    @MainThread
    public NetworkBoundResource(AppExecutors appExecutors) {
        this.appExecutors = appExecutors;
        init();
    }

    private void init(){
        // update LiveData for loading status
        result.setValue((Resource<ResultType>) Resource.loading(null));

        // Observe LiveData source from local db
        final LiveData<ResultType> dbSource = loadFromDb();
        result.addSource(dbSource, new Observer<ResultType>() {
            @Override
            public void onChanged(@Nullable ResultType resultType) {

                // Remove observer from local db. Need to decide if read local db or network
                result.removeSource(dbSource);

                // get data from network if conditions in shouldFetch(boolean) are true
                if(shouldFetch(resultType)){
                    fetchFromNetwork(dbSource);
                }
                else{ // Otherwise read data from local db
                    result.addSource(dbSource, new Observer<ResultType>() {
                        @Override
                        public void onChanged(@Nullable ResultType resultType) {

                            // Null and empty is handled in ApiResponse class
                            setValue(Resource.success(resultType));

                        }
                    });
                }
            }
        });
    }

    private void fetchFromNetwork(final LiveData<ResultType> dbSource){
//        final LiveData<ApiResponse<RequestType>> apiResponse = createCall();

        // Show data from cache (if there is any) & let user know it's loading
        result.addSource(dbSource, new Observer<ResultType>() {
            @Override
            public void onChanged(@Nullable ResultType resultType) {
                setValue(Resource.loading(resultType));
            }
        });

        final Call<ApiResponse<RequestType>> apiResponse = createCall();

        appExecutors.networkIO().submit(new Runnable() {
            @Override
            public void run() {
                try {

                    final Response<ApiResponse<RequestType>> response = apiResponse.execute();

                    // If the call doesn't return null. remove the db source
                    if(response != null){
                        result.removeSource(dbSource);
                    }

                    /*
                    3 Cases:
                    1) ApiSuccessResponse
                    2) ApiErrorResponse
                    3) ApiEmptyResponse
                 */
                    if(response.body() instanceof ApiResponse.ApiSuccessResponse){
                        appExecutors.diskIO().execute(new Runnable() {
                            @Override
                            public void run() {

                                // save response to local db
                                saveCallResult((RequestType) processResponse((ApiResponse.ApiSuccessResponse) response.body()));


                                // observe local db again since new result from network will have been saved
                                appExecutors.mainThread().execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        // we specially request a new live data,
                                        // otherwise we will get immediately last cached value,
                                        // which may not be updated with latest results received from network.
                                        // as opposed to use the @dbSource variable passed as input
                                        result.addSource(loadFromDb(), new Observer<ResultType>() {
                                            @Override
                                            public void onChanged(@Nullable ResultType resultType) {
                                                setValue(Resource.success(resultType));
                                            }
                                        });
                                    }
                                });
                            }
                        });
                    }
                    else if(response.body() instanceof ApiResponse.ApiEmptyResponse){ // empty result
                        appExecutors.mainThread().execute(new Runnable() {
                            @Override
                            public void run() {
                                result.addSource(loadFromDb(), new Observer<ResultType>() {
                                    @Override
                                    public void onChanged(@Nullable ResultType resultType) {
                                        setValue(Resource.success(resultType));
                                    }
                                });
                            }
                        });
                    }
                    else if(response.body() instanceof ApiResponse.ApiErrorResponse){ // error result
                        onFetchFailed();
                        result.addSource(dbSource, new Observer<ResultType>() {
                            @Override
                            public void onChanged(@Nullable ResultType resultType) {
                                setValue(
                                        Resource.error(
                                                ((ApiResponse.ApiErrorResponse)response.body()).getErrorMessage(),
                                                resultType
                                        )
                                );
                            }
                        });
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        // Show data from cache (if there is any) & let user know it's loading
//        result.addSource(dbSource, new Observer<ResultType>() {
//            @Override
//            public void onChanged(@Nullable ResultType resultType) {
//                setValue(Resource.loading(resultType));
//            }
//        });


//        result.addSource(apiResponse, new Observer<ApiResponse<RequestType>>() {
//            @Override
//            public void onChanged(@Nullable final ApiResponse<RequestType> requestTypeApiResponse) {
//                result.removeSource(dbSource);
//                result.removeSource(apiResponse);
//
//
//                /*
//                    3 Cases:
//                    1) ApiSuccessResponse
//                    2) ApiErrorResponse
//                    3) ApiEmptyResponse
//                 */
//                if(requestTypeApiResponse instanceof ApiResponse.ApiSuccessResponse){
//                    appExecutors.diskIO().execute(new Runnable() {
//                        @Override
//                        public void run() {
//                            saveCallResult((RequestType) processResponse((ApiResponse.ApiSuccessResponse) requestTypeApiResponse));
//
//                            appExecutors.mainThread().execute(new Runnable() {
//                                @Override
//                                public void run() {
//                                    // we specially request a new live data,
//                                    // otherwise we will get immediately last cached value,
//                                    // which may not be updated with latest results received from network.
//                                    result.addSource(loadFromDb(), new Observer<ResultType>() {
//                                        @Override
//                                        public void onChanged(@Nullable ResultType resultType) {
//                                            setValue(Resource.success(resultType));
//                                        }
//                                    });
//                                }
//                            });
//                        }
//                    });
//                }
//                else if(requestTypeApiResponse instanceof ApiResponse.ApiEmptyResponse){
//                    appExecutors.mainThread().execute(new Runnable() {
//                        @Override
//                        public void run() {
//                            result.addSource(loadFromDb(), new Observer<ResultType>() {
//                                @Override
//                                public void onChanged(@Nullable ResultType resultType) {
//                                    setValue(Resource.success(resultType));
//                                }
//                            });
//                        }
//                    });
//                }
//                else if(requestTypeApiResponse instanceof ApiResponse.ApiErrorResponse){
//                    onFetchFailed();
//                    result.addSource(dbSource, new Observer<ResultType>() {
//                        @Override
//                        public void onChanged(@Nullable ResultType resultType) {
//                            setValue(
//                                    Resource.error(
//                                            ((ApiResponse.ApiErrorResponse)requestTypeApiResponse).getErrorMessage(),
//                                            resultType
//                                            )
//                            );
//                        }
//                    });
//                }
//            }
//        });
    }

    /**
     * Setting new value to LiveData
     * Must be done on MainThread
     * @param newValue
     */
    private void setValue(Resource<ResultType> newValue) {
        if (result.getValue() != newValue) {
            result.setValue(newValue);
        }
    }

    private ResultType processResponse(ApiResponse.ApiSuccessResponse response){
        return (ResultType) response.getBody();
    }

    // Called to save the result of the API response into the database.
    abstract void saveCallResult(@NonNull RequestType item);

    // Called with the data in the database to decide whether to fetch
    // potentially updated data from the network.
    abstract boolean shouldFetch(@Nullable ResultType data);


    // Called to get the cached data from the database.
    @NonNull
    abstract LiveData<ResultType> loadFromDb();


    // Called to create the API call.
    @NonNull
    abstract Call<ApiResponse<RequestType>> createCall();

    // Called when the fetch fails. The child class may want to reset components
    // like rate limiter.

    protected abstract void onFetchFailed();

    // Returns a LiveData object that represents the resource that's implemented
    // in the base class.
    public final LiveData<Resource<ResultType>> getAsLiveData() {
        return result;
    }

}