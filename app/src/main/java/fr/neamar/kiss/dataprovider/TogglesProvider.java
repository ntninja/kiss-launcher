package fr.neamar.kiss.dataprovider;

import java.util.ArrayList;
import java.util.regex.Pattern;

import fr.neamar.kiss.R;
import fr.neamar.kiss.api.provider.Result;
import fr.neamar.kiss.dataprovider.toggle.DataItem;
import fr.neamar.kiss.dataprovider.toggle.UIEndpoint;
import fr.neamar.kiss.loader.LoadTogglesPojos;
import fr.neamar.kiss.pojo.TogglesPojo;

public class TogglesProvider extends Provider<TogglesPojo> {
    private UIEndpoint uiEndpoint;
    private String toggleName;
    
	@Override
	public void onCreate() {
		this.uiEndpoint = new UIEndpoint(this);
		
		super.onCreate();
	}

    @Override
    public void reload() {
        this.initialize(new LoadTogglesPojos(this));

        toggleName = this.getString(R.string.toggles_prefix).toLowerCase();
    }

    public ArrayList<Result> getResults(String query) {
        ArrayList<Result> results = new ArrayList<>();

        int relevance;
        String toggleNameLowerCased;
        for (TogglesPojo toggle : pojos) {
            relevance = 0;
            toggleNameLowerCased = toggle.nameNormalized;
            if (toggleNameLowerCased.startsWith(query))
                relevance = 75;
            else if (toggleNameLowerCased.contains(" " + query))
                relevance = 30;
            else if (toggleNameLowerCased.contains(query))
                relevance = 1;
            else if (toggleName.startsWith(query)) {
                // Also display for a search on "settings" for instance
                relevance = 4;
            }

            if (relevance > 0) {
                toggle.displayName = toggle.name.replaceFirst(
                        "(?i)(" + Pattern.quote(query) + ")", "{$1}");
                toggle.relevance = relevance;
                results.add(new DataItem(this.uiEndpoint, toggle));
            }
        }

        return results;
    }

    public Result findById(String id) {
        for (TogglesPojo pojo : pojos) {
            if (pojo.id.equals(id)) {
                pojo.displayName = pojo.name;
                return new DataItem(this.uiEndpoint, pojo);
            }
        }

        return null;
    }
}
