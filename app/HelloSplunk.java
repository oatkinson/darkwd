import com.splunk.Job;
import com.splunk.Service;
import com.splunk.ServiceArgs;

import java.io.*;

/**
 * Author: AtkinsonOS
 */
public class HelloSplunk {
    public static void main(String[] args) throws IOException {

        // Create a map of arguments and add login parameters
        ServiceArgs loginArgs = new ServiceArgs();
        System.out.println("username=" + loginArgs.get("username"));
        System.out.println("host=" + loginArgs.get("host"));

        // Create a Service instance and log in with the argument map
        Service service = Service.connect(loginArgs);


// Create a simple search job
        String mySearch = "search * | head 5";
        Job job = service.getJobs().create(mySearch);

// Wait for the job to finish
        while (!job.isDone()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }

// Display results
        InputStream results = job.getResults();
        String line;
        System.out.println("Results from the search job as XML:\n");
        BufferedReader br;
        br = new BufferedReader(new InputStreamReader(results, "UTF-8"));
        while ((line = br.readLine()) != null) {
            System.out.println(line);
        }
        br.close();
    }
}
