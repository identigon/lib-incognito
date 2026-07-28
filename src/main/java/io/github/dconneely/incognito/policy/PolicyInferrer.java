package io.github.dconneely.incognito.policy;

import io.github.dconneely.incognito.api.ColumnRole;
import java.util.regex.Pattern;

/**
 * Auto-infers baseline column roles based on column name heuristics and regex patterns.
 */
public class PolicyInferrer {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("(?i).*email.*");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?i).*(phone|mobile|fax).*");
    private static final Pattern NAME_PATTERN = Pattern.compile("(?i).*(first_?name|last_?name|surname|full_?name).*");
    private static final Pattern DOB_PATTERN = Pattern.compile("(?i).*(dob|birth_?date|date_of_birth).*");
    private static final Pattern SSN_PATTERN = Pattern.compile("(?i).*(ssn|social_?security|tax_?id|nhs_?num).*");

    /**
     * Infers a ColumnRole for a column based on its name and SQL type.
     *
     * @param columnName Name of the database column.
     * @return Suggested ColumnRole.
     */
    public ColumnRole inferRole(String columnName) {
        if (EMAIL_PATTERN.matcher(columnName).matches() ||
            PHONE_PATTERN.matcher(columnName).matches() ||
            NAME_PATTERN.matcher(columnName).matches() ||
            SSN_PATTERN.matcher(columnName).matches()) {
            return ColumnRole.DIRECT_ID;
        }
        if (DOB_PATTERN.matcher(columnName).matches()) {
            return ColumnRole.QUASI_ID;
        }
        return ColumnRole.PAYLOAD;
    }
}
